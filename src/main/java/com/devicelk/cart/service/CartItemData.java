package com.devicelk.cart.service;

import java.util.UUID;

/**
 * One basket line as the service layer reports it.
 * <p>
 * Module-internal: the REST layer decorates it into a client response and
 * {@code CartFacadeAdapter} narrows it into a {@code CartItemSnapshot}. Neither
 * of those is this record's shape, which is why it exists separately from both.
 * <p>
 * Money stays in minor units all the way through. A display string is produced
 * once, at the very edge, by whoever is rendering — never carried around as one.
 *
 * @param itemId         the line's own id; the handle for update and delete
 * @param productId      value reference into inventory
 * @param quantity       units requested; always greater than zero
 * @param unitPriceCents price per unit as snapshotted when the line was created
 * @param currency       ISO-4217 code for {@code unitPriceCents}
 */
public record CartItemData(
        UUID itemId,
        Long productId,
        int quantity,
        long unitPriceCents,
        String currency
) {

    /** Extended total for this line, in minor units. */
    public long lineTotalCents() {
        return unitPriceCents * quantity;
    }
}
