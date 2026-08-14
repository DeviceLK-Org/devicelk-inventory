package com.devicelk.order.api;

import com.devicelk.order.service.OrderItemData;

import java.util.UUID;

/**
 * One order line as returned to an API client.
 * <p>
 * Compare {@code CartItemResponse}, whose {@code name} is resolved from inventory
 * at render time and is null when the product has since been deleted. This one's
 * name is read straight off the order row, so it is never null and never changes:
 * an order rendered today and the same order rendered next year read identically,
 * whatever has happened to the catalogue in between. That is the payoff for
 * storing the name at checkout.
 *
 * @param itemId         the line's id
 * @param productId      the product ordered; may no longer exist in the catalogue
 * @param name           the product's name as captured at checkout
 * @param quantity       units ordered
 * @param unitPriceCents price per unit in minor units, as the customer was quoted
 * @param currency       ISO-4217 code for the amounts on this line
 * @param lineTotalCents {@code unitPriceCents × quantity}, sent so every client
 *                       agrees on the arithmetic rather than each re-deriving it
 */
public record OrderItemResponse(
        UUID itemId,
        Long productId,
        String name,
        int quantity,
        long unitPriceCents,
        String currency,
        long lineTotalCents
) {

    static OrderItemResponse from(OrderItemData item) {
        return new OrderItemResponse(
                item.itemId(),
                item.productId(),
                item.productName(),
                item.quantity(),
                item.unitPriceCents(),
                item.currency(),
                item.lineTotalCents());
    }
}
