package com.devicelk.order.service;

import com.devicelk.order.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A placed order as the service layer reports it.
 * <p>
 * Every {@code OrderService} method returns one of these rather than the
 * {@code Order} entity: the entity is a managed, mutable, lazily-initialised
 * object whose behaviour changes the moment it leaves its transaction, and
 * handing one to a controller invites exactly the bugs that produces.
 * <p>
 * {@link #totalCents} is carried as a stored value rather than recomputed from
 * {@link #items}, because that is what the order row actually holds — a total
 * derived here could quietly disagree with the figure the customer was charged,
 * and the whole point of storing it was to make that impossible.
 *
 * @param orderId    the order's id
 * @param userId     Keycloak {@code sub} of the customer — carried so callers can
 *                   assert whose order they were handed rather than assume it
 * @param status     the order's lifecycle state
 * @param totalCents order total in minor units, as stored
 * @param currency   ISO-4217 code for {@code totalCents}
 * @param placedAt   when the order was created
 * @param items      the order lines; never empty for a persisted order
 */
public record OrderData(
        UUID orderId,
        String userId,
        OrderStatus status,
        long totalCents,
        String currency,
        Instant placedAt,
        List<OrderItemData> items
) {

    /** Defensive copy, so a caller cannot mutate the returned view. */
    public OrderData {
        items = List.copyOf(items);
    }
}
