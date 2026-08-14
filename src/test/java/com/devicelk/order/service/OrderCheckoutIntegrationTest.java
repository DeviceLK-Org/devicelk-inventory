package com.devicelk.order.service;

import com.devicelk.AbstractPostgresTest;
import com.devicelk.cart.CartFacade;
import com.devicelk.cart.service.CartService;
import com.devicelk.inventory.InsufficientStockException;
import com.devicelk.inventory.InventoryFacade;
import com.devicelk.inventory.ProductSnapshot;
import com.devicelk.order.OrderStatus;
import com.devicelk.order.exception.EmptyCartException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Behavioural tests for checkout, against a real PostgreSQL 16.
 * <p>
 * <b>Nothing is mocked here, and that is the point.</b> The cart module's own
 * tests stub {@link InventoryFacade} because they are about cart behaviour and a
 * stub makes stock levels exact. These tests are about the opposite: what
 * happens across all three modules when a checkout succeeds or fails. A mocked
 * inventory could not show that stock actually moved, and — critically — could
 * not show that a failed checkout left the database untouched, because the thing
 * being tested is the real transaction spanning three schemas.
 * <p>
 * Deliberately <b>not</b> {@code @Transactional}. A test-managed transaction
 * would roll everything back at the end and, far worse, would make the
 * rollback assertions meaningless: they would pass whether or not checkout
 * actually rolled anything back. Each call commits as it would in production and
 * isolation comes from truncating between tests.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "grpc.server.port=-1"
        }
)
class OrderCheckoutIntegrationTest extends AbstractPostgresTest {

    private static final String USER_A = "keycloak-sub-order-a";
    private static final String USER_B = "keycloak-sub-order-b";
    private static final String LKR = "LKR";

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @Autowired
    private CartFacade cartFacade;

    @Autowired
    private InventoryFacade inventoryFacade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        // Mirrors the partial unique index the cart tests install: ddl-auto cannot
        // express it, and the cart service's create path is written against it.
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_carts_one_active_per_user
                    ON cart.carts (user_id) WHERE status = 'ACTIVE'
                """);
        jdbcTemplate.execute("DELETE FROM orders.order_items");
        jdbcTemplate.execute("DELETE FROM orders.orders");
        jdbcTemplate.execute("DELETE FROM cart.cart_items");
        jdbcTemplate.execute("DELETE FROM cart.carts");
        jdbcTemplate.execute("DELETE FROM inventory.stock");
        jdbcTemplate.execute("DELETE FROM inventory.products");
        jdbcTemplate.execute("DELETE FROM event_publication");
    }

    /**
     * Seeds a real product and its stock row, returning the generated id.
     * <p>
     * Inserted directly rather than through {@code ProductService} so the stock
     * figures are exactly what each test needs, without a create-then-adjust
     * dance whose intermediate states are not what is under test.
     */
    private long seedProduct(String name, long priceCents, int availableQty) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO inventory.products (name, brand, category, price_cents, currency, description)
                VALUES (?, 'TestBrand', 'LAPTOP', ?, ?, 'seeded by test')
                RETURNING id
                """, Long.class, name, priceCents, LKR);
        jdbcTemplate.update("""
                INSERT INTO inventory.stock (product_id, available_qty, reserved_qty, min_stock_threshold, version)
                VALUES (?, ?, 0, 0, 0)
                """, id, availableQty);
        return id;
    }

    private Map<String, Object> stockRow(long productId) {
        return jdbcTemplate.queryForMap(
                "SELECT available_qty, reserved_qty FROM inventory.stock WHERE product_id = ?",
                productId);
    }

    private int orderCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders.orders", Integer.class);
        return count == null ? 0 : count;
    }

    private String cartStatus(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM cart.carts WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, userId);
    }

    @Nested
    @DisplayName("checkout — happy path")
    class HappyPath {

        @Test
        @DisplayName("creates a PENDING order snapshotting name, price and quantity")
        void createsPendingOrderWithSnapshots() {
            long productId = seedProduct("Snapshot Laptop", 250_000_00L, 10);
            cartService.addItem(USER_A, productId, 2);

            OrderData order = orderService.checkout(USER_A);

            assertThat(order.orderId()).isNotNull();
            assertThat(order.userId()).isEqualTo(USER_A);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.currency()).isEqualTo(LKR);
            // Populated at flush by @CreationTimestamp, so this is only non-null
            // because checkout forces the insert before mapping the response.
            assertThat(order.placedAt()).isNotNull();
            // 2 × 250,000.00 — summed in integer cents, never through a double.
            assertThat(order.totalCents()).isEqualTo(500_000_00L);

            assertThat(order.items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(productId);
                assertThat(item.productName()).isEqualTo("Snapshot Laptop");
                assertThat(item.quantity()).isEqualTo(2);
                assertThat(item.unitPriceCents()).isEqualTo(250_000_00L);
                assertThat(item.currency()).isEqualTo(LKR);
                assertThat(item.lineTotalCents()).isEqualTo(500_000_00L);
            });
        }

        @Test
        @DisplayName("moves stock available → reserved by the ordered quantity")
        void movesStockIntoReserved() {
            long productId = seedProduct("Reserved Laptop", 100_00L, 10);
            cartService.addItem(USER_A, productId, 3);

            orderService.checkout(USER_A);

            // The move, not a decrement: units on hand are unchanged at 10, but
            // 3 of them are no longer sellable.
            assertThat(stockRow(productId))
                    .containsEntry("available_qty", 7)
                    .containsEntry("reserved_qty", 3);
        }

        @Test
        @DisplayName("closes the cart so the user starts a fresh one")
        void closesTheCart() {
            long productId = seedProduct("Closing Laptop", 100_00L, 5);
            cartService.addItem(USER_A, productId, 1);

            orderService.checkout(USER_A);

            assertThat(cartStatus(USER_A)).isEqualTo("CHECKED_OUT");
            // And the facade agrees there is no open basket any more.
            assertThat(cartFacade.getActiveCart(USER_A)).isEmpty();
        }

        @Test
        @DisplayName("totals several lines, and the stored total matches the lines")
        void totalsMultipleLines() {
            long cheap = seedProduct("Cheap", 1_50L, 10);
            long dear = seedProduct("Dear", 999_99L, 10);
            cartService.addItem(USER_A, cheap, 4);   //   6.00
            cartService.addItem(USER_A, dear, 2);    // 1999.98

            OrderData order = orderService.checkout(USER_A);

            assertThat(order.items()).hasSize(2);
            assertThat(order.totalCents()).isEqualTo(4 * 1_50L + 2 * 999_99L);
            assertThat(order.totalCents())
                    .isEqualTo(order.items().stream().mapToLong(OrderItemData::lineTotalCents).sum());
        }
    }

    @Nested
    @DisplayName("checkout — insufficient stock rolls everything back")
    class InsufficientStock {

        @Test
        @DisplayName("throws 409-mapped exception and persists nothing at all")
        void rollsBackEntirely() {
            long productId = seedProduct("Scarce Laptop", 100_00L, 5);
            // Fill the basket while stock is plentiful, then remove it from under
            // the cart — exactly the race checkout exists to catch. Going through
            // the stock row directly is the only way to change availability
            // between add-to-cart and checkout.
            cartService.addItem(USER_A, productId, 4);
            jdbcTemplate.update(
                    "UPDATE inventory.stock SET available_qty = 1 WHERE product_id = ?", productId);

            assertThatThrownBy(() -> orderService.checkout(USER_A))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 4")
                    .hasMessageContaining("available 1");

            // 1. No order row — not even an empty shell.
            assertThat(orderCount()).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM orders.order_items", Integer.class)).isZero();

            // 2. Stock completely untouched: nothing reserved, availability as it
            //    was. This is the assertion that would fail if reserveStock had
            //    committed independently of the caller's transaction.
            assertThat(stockRow(productId))
                    .containsEntry("available_qty", 1)
                    .containsEntry("reserved_qty", 0);

            // 3. The customer still has their basket, intact and open.
            assertThat(cartStatus(USER_A)).isEqualTo("ACTIVE");
            assertThat(cartFacade.getActiveCart(USER_A)).hasValueSatisfying(cart ->
                    assertThat(cart.items()).singleElement().satisfies(line -> {
                        assertThat(line.productId()).isEqualTo(productId);
                        assertThat(line.quantity()).isEqualTo(4);
                    }));
        }

        @Test
        @DisplayName("a later line failing undoes the earlier lines' reservations")
        void partialBasketReservesNothing() {
            long plentiful = seedProduct("Plentiful", 100_00L, 50);
            long scarce = seedProduct("Scarce", 100_00L, 50);
            cartService.addItem(USER_A, plentiful, 5);
            cartService.addItem(USER_A, scarce, 5);
            jdbcTemplate.update(
                    "UPDATE inventory.stock SET available_qty = 2 WHERE product_id = ?", scarce);

            assertThatThrownBy(() -> orderService.checkout(USER_A))
                    .isInstanceOf(InsufficientStockException.class);

            // The satisfiable line must not have been held. A per-line commit
            // would leave 5 units of 'plentiful' reserved for an order that was
            // never created, and nothing holding the information to release them.
            assertThat(stockRow(plentiful))
                    .containsEntry("available_qty", 50)
                    .containsEntry("reserved_qty", 0);
            assertThat(stockRow(scarce))
                    .containsEntry("available_qty", 2)
                    .containsEntry("reserved_qty", 0);
            assertThat(orderCount()).isZero();
        }
    }

    @Nested
    @DisplayName("checkout — empty cart")
    class EmptyCart {

        @Test
        @DisplayName("rejects a user who has no cart at all")
        void rejectsAbsentCart() {
            assertThatThrownBy(() -> orderService.checkout(USER_A))
                    .isInstanceOf(EmptyCartException.class)
                    .hasMessage("Cart is empty");
            assertThat(orderCount()).isZero();
        }

        @Test
        @DisplayName("rejects an open cart with no lines")
        void rejectsEmptyCart() {
            cartService.getOrCreateActiveCart(USER_A);

            assertThatThrownBy(() -> orderService.checkout(USER_A))
                    .isInstanceOf(EmptyCartException.class);
            assertThat(orderCount()).isZero();
            // The cart survives — an empty basket is not consumed by a failed
            // checkout attempt.
            assertThat(cartStatus(USER_A)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("a second checkout finds the closed cart and is rejected")
        void secondCheckoutIsRejected() {
            long productId = seedProduct("Once Only", 100_00L, 10);
            cartService.addItem(USER_A, productId, 1);
            orderService.checkout(USER_A);

            assertThatThrownBy(() -> orderService.checkout(USER_A))
                    .isInstanceOf(EmptyCartException.class);

            // Exactly one order, and exactly one unit reserved — the second
            // attempt must not have double-ordered or double-reserved.
            assertThat(orderCount()).isEqualTo(1);
            assertThat(stockRow(productId))
                    .containsEntry("available_qty", 9)
                    .containsEntry("reserved_qty", 1);
        }
    }

    @Nested
    @DisplayName("reserved units are no longer sellable")
    class ReservedUnitsAreNotSellable {

        @Test
        @DisplayName("getProduct reports the reduced availableQty, not units on hand")
        void snapshotReflectsReservation() {
            long productId = seedProduct("Availability Laptop", 100_00L, 10);
            cartService.addItem(USER_A, productId, 4);

            orderService.checkout(USER_A);

            // 10 units are still physically present; only 6 can be sold.
            assertThat(inventoryFacade.getProduct(productId))
                    .hasValueSatisfying(snapshot ->
                            assertThat(snapshot.availableQty()).isEqualTo(6));
            assertThat(inventoryFacade.checkStock(productId, 6)).isTrue();
            assertThat(inventoryFacade.checkStock(productId, 7)).isFalse();
        }

        @Test
        @DisplayName("the in-stock search filter stops matching a fully reserved product")
        void inStockFilterExcludesFullyReservedProduct() {
            long productId = seedProduct("Last One", 100_00L, 1);
            cartService.addItem(USER_A, productId, 1);

            // Visible while the unit is still sellable.
            assertThat(inStockProductIds()).contains(productId);

            orderService.checkout(USER_A);

            // Reserved, so nothing is available — the correlated EXISTS tests
            // available_qty > 0, which the reservation has driven to zero.
            assertThat(inStockProductIds()).doesNotContain(productId);
            assertThat(stockRow(productId))
                    .containsEntry("available_qty", 0)
                    .containsEntry("reserved_qty", 1);
        }

        /**
         * Runs the same predicate the {@code inStockOnly} search filter compiles
         * to, so this asserts the rule rather than a paraphrase of it.
         */
        private List<Long> inStockProductIds() {
            return jdbcTemplate.queryForList("""
                    SELECT p.id FROM inventory.products p
                    WHERE EXISTS (
                        SELECT 1 FROM inventory.stock s
                        WHERE s.product_id = p.id AND s.available_qty > 0)
                    """, Long.class);
        }
    }

    @Nested
    @DisplayName("OrderPlacedEvent")
    class EventPublication {

        @Test
        @DisplayName("is published and its listener completes after the commit")
        void eventIsPublishedAndHandled() {
            long productId = seedProduct("Event Laptop", 100_00L, 10);
            cartService.addItem(USER_A, productId, 2);

            OrderData order = orderService.checkout(USER_A);

            // The listener is @Async and fires after commit, so asserting straight
            // after checkout returns would be a race — it would pass or fail on
            // thread scheduling. Awaitility polls until the registry says the
            // publication completed, which is the durable record of the handler
            // having run to completion rather than merely having been invoked.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Map<String, Object> publication = jdbcTemplate.queryForMap("""
                        SELECT event_type, completion_date FROM event_publication
                        """);
                assertThat((String) publication.get("event_type"))
                        .isEqualTo("com.devicelk.order.OrderPlacedEvent");
                assertThat(publication.get("completion_date"))
                        .as("listener completed, so the publication is marked done")
                        .isNotNull();
            });

            // The serialised payload carries the order, so a listener never has to
            // read back what the event already told it.
            String payload = jdbcTemplate.queryForObject(
                    "SELECT serialized_event FROM event_publication", String.class);
            assertThat(payload)
                    .contains(order.orderId().toString())
                    .contains(USER_A)
                    .contains("Event Laptop");
        }

        @Test
        @DisplayName("is not published when checkout rolls back")
        void noEventForFailedCheckout() {
            long productId = seedProduct("Doomed Laptop", 100_00L, 5);
            cartService.addItem(USER_A, productId, 4);
            jdbcTemplate.update(
                    "UPDATE inventory.stock SET available_qty = 1 WHERE product_id = ?", productId);

            assertThatThrownBy(() -> orderService.checkout(USER_A))
                    .isInstanceOf(InsufficientStockException.class);

            // The publication row is written in the checkout transaction, so a
            // rollback takes it with everything else. No listener can observe an
            // order that does not exist.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication", Integer.class)).isZero();
        }
    }

    @Nested
    @DisplayName("read scoping")
    class ReadScoping {

        @Test
        @DisplayName("lists only the caller's own orders, newest first")
        void listsOwnOrdersNewestFirst() {
            long productId = seedProduct("Shared Laptop", 100_00L, 100);

            cartService.addItem(USER_A, productId, 1);
            UUID first = orderService.checkout(USER_A).orderId();
            cartService.addItem(USER_A, productId, 2);
            UUID second = orderService.checkout(USER_A).orderId();
            cartService.addItem(USER_B, productId, 1);
            orderService.checkout(USER_B);

            assertThat(orderService.findOrdersForUser(USER_A))
                    .extracting(OrderData::orderId)
                    .containsExactly(second, first);
            assertThat(orderService.findOrdersForUser(USER_B)).hasSize(1);
        }

        @Test
        @DisplayName("another user's order id resolves to not-found, not to their order")
        void cannotReadAnotherUsersOrder() {
            long productId = seedProduct("Private Laptop", 100_00L, 10);
            cartService.addItem(USER_A, productId, 1);
            UUID ordersA = orderService.checkout(USER_A).orderId();

            assertThatThrownBy(() -> orderService.getOrderForUser(ordersA, USER_B))
                    .isInstanceOf(com.devicelk.order.exception.OrderNotFoundException.class);
            // And the owner can still read it, so the scoping is real rather than
            // the row simply being unreadable.
            assertThat(orderService.getOrderForUser(ordersA, USER_A).orderId()).isEqualTo(ordersA);
        }
    }

    @Nested
    @DisplayName("published facade")
    class PublishedFacade {

        @Autowired
        private com.devicelk.order.OrderFacade orderFacade;

        @Test
        @DisplayName("exposes a placed order to other modules, unscoped by user")
        void facadeReadsOrder() {
            long productId = seedProduct("Facade Laptop", 100_00L, 10);
            cartService.addItem(USER_A, productId, 2);
            UUID orderId = orderService.checkout(USER_A).orderId();

            assertThat(orderFacade.getOrder(orderId)).hasValueSatisfying(snapshot -> {
                assertThat(snapshot.userId()).isEqualTo(USER_A);
                assertThat(snapshot.status()).isEqualTo(OrderStatus.PENDING);
                assertThat(snapshot.totalCents()).isEqualTo(200_00L);
                assertThat(snapshot.items()).singleElement().satisfies(item ->
                        assertThat(item.productName()).isEqualTo("Facade Laptop"));
            });
            assertThat(orderFacade.getOrder(UUID.randomUUID())).isEmpty();
        }
    }

    /** Guards the assumption the seeding helper is built on. */
    @Test
    @DisplayName("seeded products are visible through the inventory facade")
    void seedingProducesReadableProducts() {
        long productId = seedProduct("Sanity Laptop", 123_45L, 7);

        assertThat(inventoryFacade.getProduct(productId)).hasValueSatisfying(snapshot ->
                assertThat(snapshot)
                        .extracting(ProductSnapshot::name, ProductSnapshot::priceCents,
                                ProductSnapshot::currency, ProductSnapshot::availableQty)
                        .containsExactly("Sanity Laptop", 123_45L, LKR, 7));
    }
}
