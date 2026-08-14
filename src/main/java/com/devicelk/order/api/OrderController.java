package com.devicelk.order.api;

import com.devicelk.order.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API for the signed-in user's orders.
 * <p>
 * <b>Every endpoint operates on the caller's own orders, and only those.</b> The
 * customer is taken from the {@code sub} claim of a JWT that Spring Security has
 * already validated against Keycloak — see {@link #subjectOf(Jwt)}. There is no
 * endpoint here that accepts a user id, no request body carries one, and the
 * URLs contain no user segment to tamper with. The {@code id} in
 * {@link #getOrder} is not trusted either: the service resolves it by id
 * <i>and</i> subject together, so an id belonging to somebody else's order comes
 * back as a 404.
 * <p>
 * <b>Checkout takes no request body at all</b>, which is the point worth pausing
 * on. Everything it needs — which basket, whose, at what prices — is already
 * server-side state: the cart is found from the token, and the prices come from
 * the cart lines. Accepting an order payload from the client would mean trusting
 * the client's idea of what it is buying and what that costs, which is how a
 * checkout endpoint ends up honouring a tampered price. There is nothing here to
 * tamper with.
 * <p>
 * A thin adapter, as in the cart and inventory modules: it extracts the subject,
 * delegates to {@link OrderService}, and maps the result onto a response record.
 * No business logic, and no entity ever reaches this layer's output.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Turns the caller's active cart into an order.
     * <p>
     * 201 Created: this is the request that brings the order into existence, and
     * a repeat of it creates a second order rather than returning the first —
     * the cart it consumed is closed, so a second call finds an empty basket and
     * is rejected with a 400 rather than silently double-ordering.
     *
     * @return HTTP 201 with the placed order, in {@code PENDING}
     */
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal Jwt jwt) {
        OrderResponse body = OrderResponse.from(orderService.checkout(subjectOf(jwt)));
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    /**
     * Lists the caller's orders, newest first.
     *
     * @return HTTP 200 with the orders; an empty array for a customer who has
     *         never ordered, which is a valid state rather than a 404
     */
    @GetMapping
    public ResponseEntity<List<OrderResponse>> listOrders(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(orderService.findOrdersForUser(subjectOf(jwt)).stream()
                .map(OrderResponse::from)
                .toList());
    }

    /**
     * Reads one of the caller's orders.
     *
     * @return HTTP 200 with the order, or 404 if it does not exist or is not
     *         theirs — the two are deliberately indistinguishable
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID id) {
        return ResponseEntity.ok(
                OrderResponse.from(orderService.getOrderForUser(id, subjectOf(jwt))));
    }

    /**
     * Extracts the authenticated user's identity from their token.
     * <p>
     * The single place this module decides whose orders are being touched.
     * {@code sub} is Keycloak's immutable identifier for the user and is covered
     * by the token's signature, so it cannot be altered by the client — unlike a
     * username or email claim, which can change, or anything in a request the
     * caller composed.
     * <p>
     * The null guard is for the misconfiguration case, not the attack case: the
     * security filter chain rejects unauthenticated requests long before this
     * runs, so a null here means the order routes were left out of the
     * authenticated chain, and it is better to fail loudly than to quietly place
     * an order for a customer called "null".
     */
    private static String subjectOf(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalStateException(
                    "No authenticated subject on an order request — the order endpoints "
                            + "are not covered by the security filter chain.");
        }
        return jwt.getSubject();
    }
}
