package com.devicelk.inventory;

/**
 * Point-in-time view of a product, published by {@link InventoryFacade} for
 * other modules to consume.
 * <p>
 * Deliberately narrow: only the facts a downstream module needs to decide — does
 * the product exist, can it be sold in the quantity wanted, what does it cost.
 * Catalogue detail (brand, category, description) stays inside this module.
 * <p>
 * The price is in minor units, matching {@code Product.priceCents}, so a caller
 * that snapshots it stores inventory's exact integer rather than a decimal
 * reconstructed from a display string. {@code currency} travels with it because
 * the number is meaningless alone.
 * <p>
 * A snapshot, not a live handle: both {@code priceCents} and
 * {@code availableQty} can be stale the instant after they are read.
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
