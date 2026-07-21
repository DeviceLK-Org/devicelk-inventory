package com.devicelk.cart.api;

import java.util.UUID;

/**
 * One basket line as returned to an API client.
 *
 * @param itemId         handle for {@code PATCH}/{@code DELETE} on this line
 * @param productId      the product
 * @param name           the product's current name, resolved from inventory at
 *                       render time; {@code null} if the product no longer
 *                       exists, so a deleted product leaves the cart readable
 *                       instead of failing the whole response
 * @param quantity       units requested
 * @param unitPriceCents price per unit in minor units, as snapshotted at add-time
 * @param currency       ISO-4217 code for the amounts on this line
 * @param lineTotalCents {@code unitPriceCents × quantity}, sent so every client
 *                       agrees on the arithmetic rather than each re-deriving it
 */
public record CartItemResponse(
        UUID itemId,
        Long productId,
        String name,
        int quantity,
        long unitPriceCents,
        String currency,
        long lineTotalCents
) {
}
