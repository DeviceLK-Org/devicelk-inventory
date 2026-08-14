package com.devicelk.order;

import java.util.Optional;
import java.util.UUID;

/**
 * The order module's published API — the <b>only</b> type other modules may call.
 * <p>
 * <b>Read-only, and narrower than the module's own REST surface.</b> Placing an
 * order is checkout: it spans the cart and inventory modules in one transaction
 * and is driven by an authenticated customer request. There is no version of that
 * which another module should be able to trigger, so it is absent here. Likewise
 * absent are status transitions — when payment arrives it will move an order to
 * {@code PAID} by reacting to its own events, and the method that does it should
 * be designed then, against a real caller, rather than guessed at now.
 * <p>
 * What is here exists for the modules that will react to {@code OrderPlacedEvent}:
 * an event carries an order id, and a listener needs to be able to resolve it
 * into something it can act on.
 * <p>
 * Lives in the module's base package so Spring Modulith exposes it while
 * {@code api}, {@code domain}, {@code repository} and {@code service} stay
 * private; {@code ModularityTests} fails the build on any attempt to reach past
 * it.
 */
public interface OrderFacade {

    /**
     * Reads an order by id, regardless of who owns it.
     * <p>
     * <b>Not the path that serves {@code GET /api/v1/orders/{id}}.</b> That one
     * resolves an order by id <i>and</i> the caller's {@code sub}, so a customer
     * can never name somebody else's order. This method has no such scoping on
     * purpose, because its callers are not customers: a payment or notification
     * module handling an event has an order id and a legitimate need for the
     * order behind it, and has no user session to scope by.
     * <p>
     * The consequence is that this method must never be reached from a
     * request-handling path with a client-supplied id. Doing so would hand any
     * caller a read of any order — the precise IDOR the REST layer is written to
     * prevent.
     *
     * @param orderId the order to read
     * @return the order, or {@link Optional#empty()} if no such order exists
     */
    Optional<OrderSnapshot> getOrder(UUID orderId);
}
