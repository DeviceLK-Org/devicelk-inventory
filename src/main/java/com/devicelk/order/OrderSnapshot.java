package com.devicelk.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A placed order as published for other modules to read.
 * <p>
 * Deliberately not the {@code Order} entity: handing over a JPA aggregate would
 * expose {@code addItem} to modules with no business appending to somebody's
 * order, and drag lazy-loading behaviour across a boundary that is meant to
 * survive being turned into a network call.
 * <p>
 * Immutable and detached. Unlike a cart snapshot, most of what this carries
 * cannot go stale — an order's lines and total are write-once — but
 * {@link #status} can, and a module that holds one of these and acts on its
 * status later is acting on a guess. Re-read inside your own transaction.
 *
 * @param orderId    the order's id
 * @param userId     Keycloak {@code sub} of the customer — carried so a caller
 *                   can assert whose order it was handed rather than assume it
 * @param status     the order's lifecycle state at the moment of the read
 * @param totalCents order total in minor units
 * @param currency   ISO-4217 code for {@code totalCents}
 * @param placedAt   when the order was created
 * @param items      the order lines; never empty for a persisted order
 */
public record OrderSnapshot(
        UUID orderId,
        String userId,
        OrderStatus status,
        long totalCents,
        String currency,
        Instant placedAt,
        List<OrderItemSnapshot> items
) {

    /** Defensive copy, so the published view cannot be mutated by a caller. */
    public OrderSnapshot {
        items = List.copyOf(items);
    }
}
