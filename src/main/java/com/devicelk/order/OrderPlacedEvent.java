package com.devicelk.order;

import java.util.List;
import java.util.UUID;

/**
 * Published when a checkout commits: an order exists and its stock is reserved.
 * <p>
 * <b>Lives in the order module's published package because the publisher owns
 * the event.</b> Listeners in other modules — payment, notification, fulfilment —
 * bind to this type, which makes it part of order's public contract in exactly
 * the way {@link OrderFacade} is. Filing it in a shared package instead would
 * create a module that every side of the system depends on and nobody owns, and
 * the boundary test would stop being able to tell you who is coupled to what.
 * <p>
 * <b>Delivered after commit, and only after commit.</b> Handlers annotated
 * {@code @ApplicationModuleListener} run once the checkout transaction has
 * committed, so a listener can rely on the order being readable and the stock
 * genuinely held. The event is written to Modulith's {@code event_publication}
 * table in the same transaction, so a listener that fails — or a process that
 * dies before running it — leaves an incomplete publication to be retried rather
 * than a lost event. That is also why this is a record of plain values: it is
 * serialised to JSON and may be deserialised in a later JVM, so it must not
 * carry entities, proxies, or anything whose meaning depends on the transaction
 * it was created in.
 * <p>
 * Carries the order's contents rather than only its id. A listener given just an
 * id must call back to read what it needs, which is a second transaction against
 * a row that may have moved on, and — once order is its own service — a network
 * call back to the system that just told it something happened. The lines here
 * are what was true at the moment the order was placed, which is the thing a
 * receipt or a dispatch note is actually about.
 *
 * @param orderId    the order that was placed
 * @param userId     Keycloak {@code sub} of the customer
 * @param totalCents order total in minor units
 * @param currency   ISO-4217 code for {@code totalCents}
 * @param items      the order lines as they were snapshotted at checkout
 */
public record OrderPlacedEvent(
        UUID orderId,
        String userId,
        long totalCents,
        String currency,
        List<OrderItemSnapshot> items
) {

    /** Defensive copy, so a listener cannot mutate what other listeners will see. */
    public OrderPlacedEvent {
        items = List.copyOf(items);
    }
}
