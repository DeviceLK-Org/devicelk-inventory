package com.devicelk.cart.exception;

/**
 * Raised when a cart would hold more units of a product than inventory has
 * available.
 * <p>
 * A statement about one instant, not a reservation. Nothing is held back for
 * this cart, so passing this check is no promise the units will still be there
 * at checkout — it only rules out the basket that is already impossible.
 * <p>
 * Maps to HTTP 409: the request is well-formed, it conflicts with current state.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long productId, int requested, int available) {
        super("Insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available + ".");
    }
}
