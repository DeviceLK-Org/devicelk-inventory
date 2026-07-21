package com.devicelk.cart.exception;

import java.util.UUID;

/**
 * Raised when a line cannot be found <b>in the requesting user's cart</b>.
 * <p>
 * Deliberately conflates "no such item" with "that item belongs to someone
 * else". Both are the same answer to the caller — the item is not theirs to
 * touch — and separating them would turn this exception into an oracle for
 * probing which item ids exist.
 * <p>
 * Maps to HTTP 404.
 */
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(UUID itemId) {
        super("Cart item " + itemId + " was not found in your cart.");
    }
}
