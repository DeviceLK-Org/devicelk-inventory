package com.devicelk.order;

/**
 * One line of an {@link OrderSnapshot}, as published to other modules.
 * <p>
 * Every field is the value captured at checkout, not a live lookup — the same
 * guarantee the underlying row makes. A module rendering an invoice or a
 * dispatch note from this record shows what was actually bought, even if the
 * catalogue has since renamed or removed the product.
 *
 * @param productId      value reference into inventory; the product may no
 *                       longer exist, which is why the name travels beside it
 * @param productName    the product's name as it stood when the order was placed
 * @param quantity       units ordered; always greater than zero
 * @param unitPriceCents price per unit in minor units, as the customer was quoted
 * @param currency       ISO-4217 code for {@code unitPriceCents}
 */
public record OrderItemSnapshot(
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
