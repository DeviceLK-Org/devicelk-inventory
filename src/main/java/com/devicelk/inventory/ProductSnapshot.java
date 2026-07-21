package com.devicelk.inventory;

/**
 * Point-in-time view of a product, published by {@link InventoryFacade} for
 * other modules to consume.
 * <p>
 * Deliberately narrow: it carries only the facts a downstream module needs to
 * make a decision — does the product exist, can it be sold in the requested
 * quantity, and what does it cost right now. Catalogue detail (brand, category,
 * description) stays inside the inventory module.
 * <p>
 * The price is in <b>minor units</b>, matching {@code Product.priceCents}, so a
 * caller that snapshots it (the cart, at add-to-cart time) stores the exact
 * integer inventory holds rather than a decimal reconstructed from a display
 * string. Rendering that number for a human is the caller's concern.
 * <p>
 * {@code currency} travels with {@code priceCents} because the number is
 * meaningless without it. Every row is {@code LKR} today, but a money value
 * that has to be interpreted by convention rather than by its own content is a
 * value waiting to be misread the day that stops being true.
 * <p>
 * A <i>snapshot</i>, not a live handle: both {@code priceCents} and
 * {@code availableQty} can be stale the instant after they are read. Callers
 * that need a guarantee must re-check inside their own transaction.
 *
 * @param productId   the product's identifier
 * @param name        the product's display name
 * @param priceCents  the current unit price in minor units (cents)
 * @param currency    ISO-4217 code the price is denominated in (always 3 letters)
 * @param availableQty units on hand and sellable; {@code 0} when the product
 *                     has no stock row at all, so a missing row blocks a sale
 *                     rather than silently permitting one
 */
public record ProductSnapshot(
        Long productId,
        String name,
        long priceCents,
        String currency,
        int availableQty
) {
}
