package com.devicelk.cart;

/**
 * One line of a {@link CartSnapshot}, as published to other modules.
 * <p>
 * Carries the price the cart recorded when the item was added, not the price
 * inventory would quote now. The order module re-validates against live
 * inventory at checkout; what this record supplies is the figure the customer
 * was shown, which is the thing a pricing dispute is actually about.
 *
 * @param productId      the product this line refers to
 * @param quantity       units requested; always greater than zero
 * @param unitPriceCents price per unit in minor units, snapshotted at add-time
 * @param currency       ISO-4217 code for {@code unitPriceCents}
 */
public record CartItemSnapshot(
        Long productId,
        int quantity,
        long unitPriceCents,
        String currency
) {
}
