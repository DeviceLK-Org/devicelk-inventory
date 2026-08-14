package com.devicelk.order.api;

import com.devicelk.AbstractPostgresTest;
import com.devicelk.cart.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the order REST API: authentication, ownership scoping,
 * error mapping and response shape.
 * <p>
 * The security-critical claim under test is that an order is reachable only by
 * the subject in the bearer token. Two users are exercised throughout precisely
 * because a single-user test cannot tell correct scoping apart from no scoping
 * at all.
 * <p>
 * Inventory is real here rather than mocked, unlike the cart's API test: these
 * requests have to move actual stock for the status codes to mean anything. A
 * 409 from a mocked facade would only prove the advice is wired up, not that a
 * genuine shortage produces one.
 * <p>
 * {@code jwt()} populates the security context directly, so no Keycloak is
 * needed and no token is minted. What that does <i>not</i> cover is the decoder
 * itself — signature and issuer validation are Spring Security's, configured in
 * {@code application.yml}, and are verified by the manual smoke test against a
 * real realm rather than here.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "grpc.server.port=-1"
})
@AutoConfigureMockMvc
class OrderApiSecurityTest extends AbstractPostgresTest {

    private static final String ALICE = "keycloak-sub-alice-orders";
    private static final String BOB = "keycloak-sub-bob-orders";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CartService cartService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
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

    private long seedProduct(String name, long priceCents, int availableQty) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO inventory.products (name, brand, category, price_cents, currency, description)
                VALUES (?, 'TestBrand', 'LAPTOP', ?, 'LKR', 'seeded by test')
                RETURNING id
                """, Long.class, name, priceCents);
        jdbcTemplate.update("""
                INSERT INTO inventory.stock (product_id, available_qty, reserved_qty, min_stock_threshold, version)
                VALUES (?, ?, 0, 0, 0)
                """, id, availableQty);
        return id;
    }

    /** Attaches a validated-looking token for the given Keycloak subject. */
    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,
                                                    String subject) {
        return request.with(jwt().jwt(token -> token.subject(subject)));
    }

    /** Places an order for the given user and returns its id. */
    private UUID placeOrder(String user, long productId, int quantity) throws Exception {
        cartService.addItem(user, productId, quantity);
        String body = mockMvc.perform(as(post("/api/v1/orders/checkout"), user))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll(".*\"orderId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("every order endpoint rejects an unauthenticated request")
        void rejectsAnonymous() throws Exception {
            mockMvc.perform(post("/api/v1/orders/checkout"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/orders"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("inventory endpoints stay open, as they were before orders existed")
        void inventoryRemainsOpen() throws Exception {
            // The order chain must not have widened to cover paths other callers
            // (admin portal, AI retrieval) reach without a token.
            mockMvc.perform(get("/inventory"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/orders/checkout")
    class Checkout {

        @Test
        @DisplayName("returns 201 with the placed order")
        void returnsCreated() throws Exception {
            long productId = seedProduct("API Laptop", 150_000_00L, 10);
            cartService.addItem(ALICE, productId, 2);

            mockMvc.perform(as(post("/api/v1/orders/checkout"), ALICE))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").exists())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.totalCents").value(300_000_00L))
                    .andExpect(jsonPath("$.currency").value("LKR"))
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].name").value("API Laptop"))
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.items[0].unitPriceCents").value(150_000_00L))
                    .andExpect(jsonPath("$.items[0].lineTotalCents").value(300_000_00L))
                    // The response is a record, not an entity: no JPA leakage.
                    .andExpect(jsonPath("$.userId").doesNotExist());
        }

        @Test
        @DisplayName("an empty cart is 400, not 409")
        void emptyCartIsBadRequest() throws Exception {
            mockMvc.perform(as(post("/api/v1/orders/checkout"), ALICE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Cart is empty"));
        }

        @Test
        @DisplayName("insufficient stock is 409, and the cart survives the attempt")
        void insufficientStockIsConflict() throws Exception {
            long productId = seedProduct("Scarce API Laptop", 100_00L, 5);
            cartService.addItem(ALICE, productId, 4);
            jdbcTemplate.update(
                    "UPDATE inventory.stock SET available_qty = 1 WHERE product_id = ?", productId);

            mockMvc.perform(as(post("/api/v1/orders/checkout"), ALICE))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("Conflict"))
                    .andExpect(jsonPath("$.message").value(
                            "Insufficient stock for product " + productId
                                    + ": requested 4, available 1."));

            // Rolled back, so the basket is still there to retry with.
            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.items", hasSize(1)));
        }

        @Test
        @DisplayName("takes no body, so there is no client-supplied price to honour")
        void ignoresAnyRequestBody() throws Exception {
            long productId = seedProduct("Tamper Laptop", 500_00L, 10);
            cartService.addItem(ALICE, productId, 1);

            // A caller trying to dictate the price is not merely rejected — the
            // endpoint has nowhere to read it from, so the order is placed at the
            // price the cart recorded.
            mockMvc.perform(as(post("/api/v1/orders/checkout"), ALICE)
                            .contentType("application/json")
                            .content("{\"totalCents\":1,\"items\":[]}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.totalCents").value(500_00L));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/orders")
    class ListOrders {

        @Test
        @DisplayName("returns only the caller's own orders, newest first")
        void listsOwnOrdersOnly() throws Exception {
            long productId = seedProduct("Listed Laptop", 100_00L, 100);
            placeOrder(ALICE, productId, 1);
            placeOrder(ALICE, productId, 3);
            placeOrder(BOB, productId, 1);

            mockMvc.perform(as(get("/api/v1/orders"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    // Newest first: the 3-unit order was placed second.
                    .andExpect(jsonPath("$[0].items[0].quantity").value(3))
                    .andExpect(jsonPath("$[1].items[0].quantity").value(1));

            mockMvc.perform(as(get("/api/v1/orders"), BOB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        @DisplayName("a customer who has never ordered gets an empty array, not a 404")
        void emptyHistoryIsOk() throws Exception {
            mockMvc.perform(as(get("/api/v1/orders"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/orders/{id} — IDOR")
    class GetOrder {

        @Test
        @DisplayName("the owner reads their order")
        void ownerCanRead() throws Exception {
            long productId = seedProduct("Owned Laptop", 100_00L, 10);
            UUID orderId = placeOrder(ALICE, productId, 2);

            mockMvc.perform(as(get("/api/v1/orders/" + orderId), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.items[0].name").value("Owned Laptop"));
        }

        @Test
        @DisplayName("another user gets 404 for the very same id")
        void otherUserGetsNotFound() throws Exception {
            long productId = seedProduct("Private Laptop", 100_00L, 10);
            UUID alicesOrder = placeOrder(ALICE, productId, 1);

            // The decisive pairing: Bob is refused the exact id Alice can read,
            // so this is scoping rather than the order being unreadable at all.
            mockMvc.perform(as(get("/api/v1/orders/" + alicesOrder), BOB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Not Found"));

            mockMvc.perform(as(get("/api/v1/orders/" + alicesOrder), ALICE))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an unknown id is 404, indistinguishable from someone else's")
        void unknownIdIsNotFound() throws Exception {
            mockMvc.perform(as(get("/api/v1/orders/" + UUID.randomUUID()), ALICE))
                    .andExpect(status().isNotFound());
        }
    }
}
