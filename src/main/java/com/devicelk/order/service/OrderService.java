package com.devicelk.order.service;

import com.devicelk.inventory.InsufficientStockException;
import com.devicelk.order.exception.EmptyCartException;
import com.devicelk.order.exception.OrderNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for placing and reading orders.
 * <p>
 * <b>{@code userId} must come from the authenticated security context.</b> This
 * layer cannot tell a real subject from a forged one, so it trusts what it is
 * given — which is precisely why the layer above must never take it from a
 * request body, a path variable, or a query parameter. The same rule the cart
 * module runs on, and for the same reason.
 */
public interface OrderService {

    /**
     * Turns the user's active cart into a placed order.
     * <p>
     * The one operation in this application that spans three modules, and the
     * only one whose correctness depends on all of it happening together:
     * inventory holds the units, an order records the purchase, and the cart
     * closes. See the implementation for what is guaranteed and why.
     *
     * @param userId Keycloak {@code sub} of the customer
     * @return the order as placed, in {@code PENDING}
     * @throws EmptyCartException         if the user has no active cart, or an
     *                                    active cart with no lines
     * @throws InsufficientStockException if inventory cannot hold the units for
     *                                    any line; nothing is persisted and no
     *                                    stock moves
     */
    OrderData checkout(String userId);

    /**
     * Reads an order by id, regardless of owner.
     * <p>
     * Backs {@code OrderFacade.getOrder} for modules reacting to an
     * {@code OrderPlacedEvent}, which hold an order id and no user session.
     * <b>Not for request-handling paths</b> — those must scope by the caller's
     * subject, or any customer can read any order.
     *
     * @param orderId the order to read
     * @return the order, or {@link Optional#empty()} if no such order exists
     */
    Optional<OrderData> findOrder(UUID orderId);

    /**
     * Lists a user's own orders, newest first.
     *
     * @param userId Keycloak {@code sub} of the customer
     * @return their orders, newest first; empty if they have never ordered
     */
    List<OrderData> findOrdersForUser(String userId);

    /**
     * Reads one of a user's own orders.
     * <p>
     * Resolved by id <b>and</b> owner in a single query, so another user's order
     * matches nothing rather than being loaded and then rejected. That ordering
     * matters: a check applied after loading is one a future caller can forget,
     * whereas this way there is no code path that holds someone else's order at
     * all.
     *
     * @param orderId the order id, as supplied by the client
     * @param userId  Keycloak {@code sub} of the caller, from the validated token
     * @return the order
     * @throws OrderNotFoundException if no such order exists <i>or</i> it belongs
     *                                to another user — the two are deliberately
     *                                indistinguishable
     */
    OrderData getOrderForUser(UUID orderId, String userId);
}
