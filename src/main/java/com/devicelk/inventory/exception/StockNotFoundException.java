package com.devicelk.inventory.exception;

/**
 * Raised when a product exists but its {@code inventory.stock} row does not.
 * <p>
 * Distinct from {@link ProductNotFoundException} because the two mean very
 * different things: that one is an ordinary miss on an id the caller supplied,
 * whereas this signals that a product was created without the stock row that
 * should accompany it. Reporting it as "product not found" would send whoever
 * is debugging it looking for the wrong problem.
 */
public class StockNotFoundException extends RuntimeException {

    public StockNotFoundException(Long productId) {
        super("Stock record missing for product " + productId);
    }
}
