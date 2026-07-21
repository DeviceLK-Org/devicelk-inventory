package com.devicelk.cart.service;

import com.devicelk.cart.domain.CartStatus;
import com.devicelk.cart.exception.CartItemNotFoundException;
import com.devicelk.cart.exception.InsufficientStockException;
import com.devicelk.cart.exception.UnknownProductException;
import com.devicelk.inventory.InventoryFacade;
import com.devicelk.inventory.ProductSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.devicelk.AbstractPostgresTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for {@link CartService}, against a real PostgreSQL 16.
 * <p>
 * A real database rather than an in-memory one because several of the rules
 * under test are enforced by the schema, not by Java: the
 * {@code (cart_id, product_id)} unique constraint behind increment-not-duplicate,
 * and the partial unique index behind one-active-cart-per-user. An in-memory
 * substitute would let those tests pass while testing nothing.
 * <p>
 * {@link InventoryFacade} is mocked. It is the module boundary, so stubbing it
 * is what keeps these tests about cart behaviour instead of inventory fixtures —
 * and it makes stock levels exact rather than something to arrange by seeding
 * products. The static dependency (cart depends on the facade and nothing else
 * in inventory) is asserted separately by {@code ModularityTests}.
 * <p>
 * Tests are deliberately <b>not</b> {@code @Transactional}. Each service call
 * commits, as it would in production — which the concurrency test requires, and
 * which stops the others from passing on state that a real caller would never
 * see. Isolation comes from truncating between tests instead.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                // The gRPC server has no part in these tests and would only
                // contend for a port.
                "grpc.server.port=-1"
        }
)
class CartServiceIntegrationTest extends AbstractPostgresTest {

    private static final String USER_A = "keycloak-sub-user-a";
    private static final String USER_B = "keycloak-sub-user-b";

    private static final long PRODUCT_ID = 1001L;
    private static final long PRICE_CENTS = 32999700L;
    private static final String LKR = "LKR";

    @Autowired
    private CartService cartService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private InventoryFacade inventoryFacade;

    @BeforeEach
    void resetDatabase() {
        // The partial unique index is not something ddl-auto can express, so it
        // is applied here exactly as it was applied to the dev database. Without
        // it the concurrency test below would silently prove nothing.
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_carts_one_active_per_user
                    ON cart.carts (user_id) WHERE status = 'ACTIVE'
                """);
        jdbcTemplate.execute("DELETE FROM cart.cart_items");
        jdbcTemplate.execute("DELETE FROM cart.carts");
    }

    /** Stubs inventory's answer for the default product. */
    private void stubProduct(int availableQty) {
        stubProduct(PRODUCT_ID, PRICE_CENTS, availableQty);
    }

    private void stubProduct(long productId, long priceCents, int availableQty) {
        when(inventoryFacade.getProduct(productId)).thenReturn(Optional.of(
                new ProductSnapshot(productId, "Test Product " + productId,
                        priceCents, LKR, availableQty)));
    }

    private int activeCartCount(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cart.carts WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Nested
    @DisplayName("getOrCreateActiveCart")
    class GetOrCreate {

        @Test
        @DisplayName("creates an empty ACTIVE cart for a user who has none")
        void createsLazily() {
            CartData cart = cartService.getOrCreateActiveCart(USER_A);

            assertThat(cart.cartId()).isNotNull();
            assertThat(cart.userId()).isEqualTo(USER_A);
            assertThat(cart.status()).isEqualTo(CartStatus.ACTIVE);
            assertThat(cart.items()).isEmpty();
            assertThat(cart.totalCents()).isZero();
            assertThat(activeCartCount(USER_A)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns the same cart on a second call rather than a new one")
        void isIdempotent() {
            UUID first = cartService.getOrCreateActiveCart(USER_A).cartId();
            UUID second = cartService.getOrCreateActiveCart(USER_A).cartId();

            assertThat(second).isEqualTo(first);
            assertThat(activeCartCount(USER_A)).isEqualTo(1);
        }

        @Test
        @DisplayName("findActiveCart does not create a cart as a side effect")
        void findDoesNotCreate() {
            assertThat(cartService.findActiveCart(USER_A)).isEmpty();
            assertThat(activeCartCount(USER_A)).isZero();
        }

        @Test
        @DisplayName("concurrent first-time calls yield exactly one cart and no failure")
        void concurrentCreationYieldsOneCart() throws Exception {
            int threads = 8;
            String freshUser = "keycloak-sub-" + UUID.randomUUID();

            // Every thread blocks on the same latch so the inserts genuinely
            // overlap instead of politely queueing behind each other.
            CountDownLatch startLine = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Callable<UUID>> attempts = java.util.stream.IntStream.range(0, threads)
                        .<Callable<UUID>>mapToObj(i -> () -> {
                            startLine.await();
                            return cartService.getOrCreateActiveCart(freshUser).cartId();
                        })
                        .toList();

                List<Future<UUID>> futures = attempts.stream().map(pool::submit).toList();
                startLine.countDown();

                // get() rethrows anything a worker threw: a 500-shaped failure
                // (DataIntegrityViolationException surfacing raw, or the aborted
                // -transaction error) fails the test here rather than being
                // swallowed into a count that happens to look right.
                List<UUID> cartIds = futures.stream()
                        .map(f -> {
                            try {
                                return f.get(30, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new AssertionError(
                                        "getOrCreateActiveCart failed under concurrency", e);
                            }
                        })
                        .toList();

                assertThat(cartIds).hasSize(threads).containsOnly(cartIds.get(0));
                assertThat(activeCartCount(freshUser)).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("addItem")
    class AddItem {

        @Test
        @DisplayName("adds a new line, snapshotting price and currency from inventory")
        void addsNewLine() {
            stubProduct(10);

            CartData cart = cartService.addItem(USER_A, PRODUCT_ID, 2);

            assertThat(cart.items()).hasSize(1);
            CartItemData line = cart.items().get(0);
            assertThat(line.productId()).isEqualTo(PRODUCT_ID);
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.unitPriceCents()).isEqualTo(PRICE_CENTS);
            assertThat(line.currency()).isEqualTo(LKR);
            assertThat(line.lineTotalCents()).isEqualTo(PRICE_CENTS * 2);
            assertThat(cart.totalCents()).isEqualTo(PRICE_CENTS * 2);
        }

        @Test
        @DisplayName("increments the existing line instead of duplicating the product")
        void incrementsRatherThanDuplicating() {
            stubProduct(10);

            cartService.addItem(USER_A, PRODUCT_ID, 2);
            CartData cart = cartService.addItem(USER_A, PRODUCT_ID, 3);

            assertThat(cart.items()).hasSize(1);
            assertThat(cart.items().get(0).quantity()).isEqualTo(5);

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM cart.cart_items WHERE product_id = ?",
                    Integer.class, PRODUCT_ID);
            assertThat(rows).isEqualTo(1);
        }

        @Test
        @DisplayName("keeps the original price when an existing line grows")
        void doesNotRepriceOnIncrement() {
            stubProduct(10);
            cartService.addItem(USER_A, PRODUCT_ID, 1);

            // Inventory re-prices the product between the two adds.
            stubProduct(PRODUCT_ID, 99999999L, 10);
            CartData cart = cartService.addItem(USER_A, PRODUCT_ID, 1);

            assertThat(cart.items().get(0).quantity()).isEqualTo(2);
            assertThat(cart.items().get(0).unitPriceCents())
                    .as("the customer keeps the price quoted when they added the product")
                    .isEqualTo(PRICE_CENTS);
        }

        @Test
        @DisplayName("checks stock against the resulting total, not just the increment")
        void checksTotalNotIncrement() {
            stubProduct(5);
            cartService.addItem(USER_A, PRODUCT_ID, 4);

            // 2 more is itself within stock, but 4 + 2 = 6 is not.
            assertThatThrownBy(() -> cartService.addItem(USER_A, PRODUCT_ID, 2))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 6")
                    .hasMessageContaining("available 5");
        }

        @Test
        @DisplayName("rejects a quantity beyond available stock")
        void rejectsBeyondStock() {
            stubProduct(3);

            assertThatThrownBy(() -> cartService.addItem(USER_A, PRODUCT_ID, 4))
                    .isInstanceOf(InsufficientStockException.class);

            assertThat(cartService.getOrCreateActiveCart(USER_A).items()).isEmpty();
        }

        @Test
        @DisplayName("rejects a product inventory does not have")
        void rejectsUnknownProduct() {
            when(inventoryFacade.getProduct(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartService.addItem(USER_A, 404404L, 1))
                    .isInstanceOf(UnknownProductException.class)
                    .hasMessageContaining("404404");
        }

        @Test
        @DisplayName("rejects a non-positive quantity")
        void rejectsNonPositiveQuantity() {
            stubProduct(10);

            assertThatThrownBy(() -> cartService.addItem(USER_A, PRODUCT_ID, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("does not reserve stock — inventory is never asked to move units")
        void doesNotReserveStock() {
            stubProduct(10);
            cartService.addItem(USER_A, PRODUCT_ID, 4);

            // InventoryFacade is read-only by construction; this asserts the cart
            // module did not somehow acquire a write path to it.
            org.mockito.Mockito.verify(inventoryFacade, org.mockito.Mockito.atLeastOnce())
                    .getProduct(PRODUCT_ID);
            org.mockito.Mockito.verifyNoMoreInteractions(inventoryFacade);
        }
    }

    @Nested
    @DisplayName("updateItemQuantity")
    class UpdateQuantity {

        @Test
        @DisplayName("sets an absolute quantity when stock allows")
        void updatesQuantity() {
            stubProduct(10);
            UUID itemId = cartService.addItem(USER_A, PRODUCT_ID, 2).items().get(0).itemId();

            CartData cart = cartService.updateItemQuantity(USER_A, itemId, 7);

            assertThat(cart.items()).hasSize(1);
            assertThat(cart.items().get(0).quantity()).isEqualTo(7);
        }

        @Test
        @DisplayName("re-validates stock for the new quantity")
        void revalidatesStock() {
            stubProduct(10);
            UUID itemId = cartService.addItem(USER_A, PRODUCT_ID, 2).items().get(0).itemId();

            // Stock drops after the item was added.
            stubProduct(PRODUCT_ID, PRICE_CENTS, 5);

            assertThatThrownBy(() -> cartService.updateItemQuantity(USER_A, itemId, 6))
                    .isInstanceOf(InsufficientStockException.class);

            assertThat(cartService.getOrCreateActiveCart(USER_A).items().get(0).quantity())
                    .as("a rejected update leaves the line untouched")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("removes the line when the new quantity is zero")
        void zeroRemovesLine() {
            stubProduct(10);
            UUID itemId = cartService.addItem(USER_A, PRODUCT_ID, 2).items().get(0).itemId();

            CartData cart = cartService.updateItemQuantity(USER_A, itemId, 0);

            assertThat(cart.items()).isEmpty();
            assertThat(cart.totalCents()).isZero();
        }

        @Test
        @DisplayName("does not re-snapshot the price")
        void doesNotReprice() {
            stubProduct(10);
            UUID itemId = cartService.addItem(USER_A, PRODUCT_ID, 2).items().get(0).itemId();

            stubProduct(PRODUCT_ID, 12345678L, 10);
            CartData cart = cartService.updateItemQuantity(USER_A, itemId, 3);

            assertThat(cart.items().get(0).unitPriceCents()).isEqualTo(PRICE_CENTS);
        }
    }

    @Nested
    @DisplayName("removeItem and clearCart")
    class RemoveAndClear {

        @Test
        @DisplayName("removes a single line")
        void removesLine() {
            stubProduct(10);
            stubProduct(2002L, 500L, 10);
            cartService.addItem(USER_A, PRODUCT_ID, 1);
            UUID second = cartService.addItem(USER_A, 2002L, 1).items().stream()
                    .filter(i -> i.productId() == 2002L)
                    .findFirst().orElseThrow().itemId();

            CartData cart = cartService.removeItem(USER_A, second);

            assertThat(cart.items()).hasSize(1);
            assertThat(cart.items().get(0).productId()).isEqualTo(PRODUCT_ID);
        }

        @Test
        @DisplayName("clearing empties the cart but leaves it ACTIVE")
        void clearEmptiesCart() {
            stubProduct(10);
            cartService.addItem(USER_A, PRODUCT_ID, 3);

            CartData cart = cartService.clearCart(USER_A);

            assertThat(cart.items()).isEmpty();
            assertThat(cart.status()).isEqualTo(CartStatus.ACTIVE);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM cart.cart_items", Integer.class)).isZero();
        }

        @Test
        @DisplayName("clearing a cart that does not exist yet succeeds")
        void clearIsIdempotent() {
            assertThatNoException().isThrownBy(() -> cartService.clearCart(USER_A));
            assertThat(cartService.getOrCreateActiveCart(USER_A).items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("a user cannot update an item in another user's cart")
        void cannotUpdateAnotherUsersItem() {
            stubProduct(10);
            UUID usersAItem = cartService.addItem(USER_A, PRODUCT_ID, 2).items().get(0).itemId();
            cartService.getOrCreateActiveCart(USER_B);

            assertThatThrownBy(() -> cartService.updateItemQuantity(USER_B, usersAItem, 5))
                    .isInstanceOf(CartItemNotFoundException.class);

            assertThat(cartService.getOrCreateActiveCart(USER_A).items().get(0).quantity())
                    .as("user A's line is untouched")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a user cannot remove an item from another user's cart")
        void cannotRemoveAnotherUsersItem() {
            stubProduct(10);
            UUID usersAItem = cartService.addItem(USER_A, PRODUCT_ID, 2).items().get(0).itemId();
            cartService.getOrCreateActiveCart(USER_B);

            assertThatThrownBy(() -> cartService.removeItem(USER_B, usersAItem))
                    .isInstanceOf(CartItemNotFoundException.class);

            assertThat(cartService.getOrCreateActiveCart(USER_A).items()).hasSize(1);
        }

        @Test
        @DisplayName("a user with no cart at all cannot touch another user's item")
        void userWithoutCartCannotReach() {
            stubProduct(10);
            UUID usersAItem = cartService.addItem(USER_A, PRODUCT_ID, 2).items().get(0).itemId();

            // USER_B has never had a cart: the failure must look identical to the
            // case above rather than leaking that the item exists elsewhere.
            assertThatThrownBy(() -> cartService.removeItem(USER_B, usersAItem))
                    .isInstanceOf(CartItemNotFoundException.class);
        }

        @Test
        @DisplayName("clearing one user's cart leaves the other's alone")
        void clearIsScopedToOneUser() {
            stubProduct(10);
            cartService.addItem(USER_A, PRODUCT_ID, 1);
            cartService.addItem(USER_B, PRODUCT_ID, 1);

            cartService.clearCart(USER_B);

            assertThat(cartService.getOrCreateActiveCart(USER_A).items()).hasSize(1);
            assertThat(cartService.getOrCreateActiveCart(USER_B).items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("markCheckedOut")
    class MarkCheckedOut {

        @Test
        @DisplayName("closes the cart so it is no longer the user's active one")
        void closesCart() {
            stubProduct(10);
            UUID cartId = cartService.addItem(USER_A, PRODUCT_ID, 1).cartId();

            cartService.markCheckedOut(cartId);

            assertThat(cartService.findActiveCart(USER_A)).isEmpty();
            assertThat(activeCartCount(USER_A)).isZero();
        }

        @Test
        @DisplayName("a checked-out cart frees the user to start a new one")
        void allowsNewCartAfterCheckout() {
            stubProduct(10);
            UUID first = cartService.addItem(USER_A, PRODUCT_ID, 1).cartId();
            cartService.markCheckedOut(first);

            UUID second = cartService.getOrCreateActiveCart(USER_A).cartId();

            assertThat(second).isNotEqualTo(first);
            assertThat(activeCartCount(USER_A)).isEqualTo(1);
        }

        @Test
        @DisplayName("checking out twice is rejected, not silently repeated")
        void rejectsDoubleCheckout() {
            UUID cartId = cartService.getOrCreateActiveCart(USER_A).cartId();
            cartService.markCheckedOut(cartId);

            assertThatThrownBy(() -> cartService.markCheckedOut(cartId))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an unknown cart id is rejected")
        void rejectsUnknownCart() {
            assertThatThrownBy(() -> cartService.markCheckedOut(UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
