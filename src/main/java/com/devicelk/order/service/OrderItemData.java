package com.devicelk.order.service;

import java.util.UUID;

/**
 * One order line as the service layer reports it.
 * <p>
 * Module-internal: the REST layer decorates it into a client response and
 * {@code OrderFacadeAdapter} narrows it into an {@code OrderItemSnapshot}.
 * Neither of those is this record's shape, which is why it exists separately
 * from both — the same three-layer split the cart module uses.
 * <p>
 * Money stays in minor units all the way through. A display string is produced
 * once, at the very edge, by whoever is rendering.
 *
 * @param itemId         the line's own id
 * @param productId      value reference into inventory; the product may since
 *                       have been removed, which is why the name is stored
 * @param productName    the product's name as captured at checkout
 * @param quantity       units ordered; always greater than zero
 * @param unitPriceCents price per unit as the customer was quoted it
 * @param currency       ISO-4217 code for {@code unitPriceCents}
 */
public record OrderItemData(
        UUID itemId,
        Long productId,
        String productName,
        int quantity,
        long unitPriceCents,
        String currency
) {

    /** Extended total for this line, in minor units. */
    public long lineTotalCents() {
        return unitPriceCents * quantity;
    }
}
