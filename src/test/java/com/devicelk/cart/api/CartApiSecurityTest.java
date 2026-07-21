package com.devicelk.cart.api;

import com.devicelk.AbstractPostgresTest;
import com.devicelk.inventory.InventoryFacade;
import com.devicelk.inventory.ProductSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the cart REST API: authentication, ownership scoping,
 * error mapping and response shape.
 * <p>
 * The security-critical claim under test is that a cart is reachable only by the
 * subject in the bearer token. Two users are exercised throughout precisely
 * because a single-user test cannot tell correct scoping apart from no scoping
 * at all.
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
class CartApiSecurityTest extends AbstractPostgresTest {

    private static final String ALICE = "keycloak-sub-alice";
    private static final String BOB = "keycloak-sub-bob";

    private static final long PRODUCT_ID = 5001L;
    private static final long OTHER_PRODUCT_ID = 5002L;
    private static final long PRICE_CENTS = 249900L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InventoryFacade inventoryFacade;

    /**
     * The products inventory is pretending to have.
     * <p>
     * Both stubs answer from this map rather than from each other. An earlier
     * version had the {@code getProducts} stub call {@code getProduct} on the
     * mock to build its result, which registered invocations the production code
     * never made — and quietly broke the one test whose whole purpose is counting
     * those invocations.
     */
    private final Map<Long, ProductSnapshot> catalogue = new HashMap<>();

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_carts_one_active_per_user
                    ON cart.carts (user_id) WHERE status = 'ACTIVE'
                """);
        jdbcTemplate.execute("DELETE FROM cart.cart_items");
        jdbcTemplate.execute("DELETE FROM cart.carts");

        catalogue.clear();
        when(inventoryFacade.getProducts(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return ids.stream()
                    .map(catalogue::get)
                    .filter(Objects::nonNull)
                    .toList();
        });

        stubProduct(PRODUCT_ID, "Dell XPS 15", PRICE_CENTS, 20);
        stubProduct(OTHER_PRODUCT_ID, "Logitech MX Master", 3500L, 20);
    }

    private void stubProduct(long productId, String name, long priceCents, int availableQty) {
        ProductSnapshot snapshot =
                new ProductSnapshot(productId, name, priceCents, "LKR", availableQty);
        catalogue.put(productId, snapshot);
        when(inventoryFacade.getProduct(productId)).thenReturn(Optional.of(snapshot));
    }

    /** Attaches a bearer identity for the given Keycloak subject. */
    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,
                                                    String subject) {
        return request.with(jwt().jwt(token -> token.subject(subject)));
    }

    private String addItemBody(long productId, int quantity) throws Exception {
        return objectMapper.writeValueAsString(
                new AddItemRequest(productId, quantity));
    }

    /** Adds an item as the given user and returns the created line's id. */
    private UUID addItemAs(String subject, long productId, int quantity) throws Exception {
        String json = mockMvc.perform(as(post("/api/v1/cart/items"), subject)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addItemBody(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode items = objectMapper.readTree(json).get("items");
        for (JsonNode item : items) {
            if (item.get("productId").asLong() == productId) {
                return UUID.fromString(item.get("itemId").asText());
            }
        }
        throw new AssertionError("added item not present in response: " + json);
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("every cart endpoint rejects a request with no token")
        void noTokenIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/v1/cart"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/cart/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addItemBody(PRODUCT_ID, 1)))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(patch("/api/v1/cart/items/" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":2}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(delete("/api/v1/cart/items/" + UUID.randomUUID()))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(delete("/api/v1/cart"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an unauthenticated request creates no cart as a side effect")
        void noTokenTouchesNothing() throws Exception {
            mockMvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM cart.carts", Integer.class)).isZero();
        }

        @Test
        @DisplayName("a valid token returns 200 and lazily creates that subject's cart")
        void validTokenCreatesCartForSubject() throws Exception {
            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.totalCents").value(0));

            // The row is keyed by the token's sub, which is the whole point.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT user_id FROM cart.carts", String.class)).isEqualTo(ALICE);
        }

        @Test
        @DisplayName("the cart is keyed by the token subject, not by anything in the request")
        void userIdComesFromSubject() throws Exception {
            addItemAs(ALICE, PRODUCT_ID, 2);

            Integer alicesCarts = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM cart.carts WHERE user_id = ?", Integer.class, ALICE);
            assertThat(alicesCarts).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM cart.carts", Integer.class))
                    .as("no cart exists for any other identity")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("ownership scoping")
    class OwnershipScoping {

        @Test
        @DisplayName("one user's cart is invisible to another")
        void cartsAreIsolated() throws Exception {
            addItemAs(ALICE, PRODUCT_ID, 3);

            mockMvc.perform(as(get("/api/v1/cart"), BOB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());

            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].quantity").value(3));
        }

        @Test
        @DisplayName("a user cannot PATCH a line in another user's cart")
        void cannotPatchAnotherUsersLine() throws Exception {
            UUID alicesItem = addItemAs(ALICE, PRODUCT_ID, 2);

            mockMvc.perform(as(patch("/api/v1/cart/items/" + alicesItem), BOB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":99}"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(jsonPath("$.items[0].quantity").value(2));
        }

        @Test
        @DisplayName("a user cannot DELETE a line in another user's cart")
        void cannotDeleteAnotherUsersLine() throws Exception {
            UUID alicesItem = addItemAs(ALICE, PRODUCT_ID, 2);

            mockMvc.perform(as(delete("/api/v1/cart/items/" + alicesItem), BOB))
                    .andExpect(status().isNotFound());

            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(jsonPath("$.items.length()").value(1));
        }

        @Test
        @DisplayName("clearing one cart leaves the other intact")
        void clearIsScoped() throws Exception {
            addItemAs(ALICE, PRODUCT_ID, 1);
            addItemAs(BOB, PRODUCT_ID, 1);

            mockMvc.perform(as(delete("/api/v1/cart"), BOB))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());

            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(jsonPath("$.items.length()").value(1));
        }
    }

    @Nested
    @DisplayName("response rendering")
    class ResponseRendering {

        @Test
        @DisplayName("renders names, per-line totals and a cart total")
        void rendersFullCart() throws Exception {
            addItemAs(ALICE, PRODUCT_ID, 2);
            addItemAs(ALICE, OTHER_PRODUCT_ID, 3);

            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cartId").isNotEmpty())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.currency").value("LKR"))
                    .andExpect(jsonPath("$.items[?(@.productId == 5001)].name")
                            .value("Dell XPS 15"))
                    .andExpect(jsonPath("$.items[?(@.productId == 5001)].lineTotalCents")
                            .value((int) (PRICE_CENTS * 2)))
                    .andExpect(jsonPath("$.totalCents")
                            .value((int) (PRICE_CENTS * 2 + 3500L * 3)));
        }

        @Test
        @DisplayName("resolves every line's name in ONE batched inventory call, not one per line")
        void namesAreResolvedInOneCall() throws Exception {
            addItemAs(ALICE, PRODUCT_ID, 1);
            addItemAs(ALICE, OTHER_PRODUCT_ID, 1);

            // Only count what the render itself does.
            clearInvocations(inventoryFacade);

            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2));

            verify(inventoryFacade, times(1)).getProducts(anyList());
            // A per-line lookup would show up here; rendering must not use it.
            verify(inventoryFacade, times(0)).getProduct(anyLong());
        }

        @Test
        @DisplayName("a line whose product vanished still renders, with a null name")
        void survivesDeletedProduct() throws Exception {
            addItemAs(ALICE, PRODUCT_ID, 1);

            // Inventory forgets the product after it was added to the cart.
            when(inventoryFacade.getProducts(anyList())).thenReturn(List.of());

            mockMvc.perform(as(get("/api/v1/cart"), ALICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].name").doesNotExist())
                    .andExpect(jsonPath("$.items[0].unitPriceCents").value((int) PRICE_CENTS));
        }
    }

    @Nested
    @DisplayName("error mapping")
    class ErrorMapping {

        @Test
        @DisplayName("unknown product -> 404, not 500")
        void unknownProductIsNotFound() throws Exception {
            when(inventoryFacade.getProduct(9999L)).thenReturn(Optional.empty());

            mockMvc.perform(as(post("/api/v1/cart/items"), ALICE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addItemBody(9999L, 1)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }

        @Test
        @DisplayName("quantity beyond stock -> 409, not 500")
        void insufficientStockIsConflict() throws Exception {
            stubProduct(PRODUCT_ID, "Dell XPS 15", PRICE_CENTS, 2);

            mockMvc.perform(as(post("/api/v1/cart/items"), ALICE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addItemBody(PRODUCT_ID, 5)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("non-positive add quantity -> 400, not the inventory advice's 409")
        void nonPositiveQuantityIsBadRequest() throws Exception {
            mockMvc.perform(as(post("/api/v1/cart/items"), ALICE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addItemBody(PRODUCT_ID, 0)))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(as(post("/api/v1/cart/items"), ALICE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addItemBody(PRODUCT_ID, -3)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("unknown item id -> 404, not 500")
        void unknownItemIsNotFound() throws Exception {
            mockMvc.perform(as(get("/api/v1/cart"), ALICE)).andExpect(status().isOk());

            mockMvc.perform(as(delete("/api/v1/cart/items/" + UUID.randomUUID()), ALICE))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PATCH to zero removes the line rather than failing")
        void patchToZeroRemoves() throws Exception {
            UUID itemId = addItemAs(ALICE, PRODUCT_ID, 4);

            mockMvc.perform(as(patch("/api/v1/cart/items/" + itemId), ALICE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }
    }
}
