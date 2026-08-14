package com.devicelk.order.exception;

/**
 * Raised when checkout is attempted on a basket with nothing in it.
 * <p>
 * Covers both shapes of "empty": a user with no {@code ACTIVE} cart at all, and
 * one whose cart exists but holds no lines. They are the same event from the
 * customer's side — there is nothing to buy — and distinguishing them in the
 * response would leak the detail that a cart record exists without telling the
 * caller anything they could act on.
 * <p>
 * Maps to HTTP 400 rather than 409. A conflict implies the request could succeed
 * once the world changes; this one cannot succeed until the caller does
 * something different, namely put an item in the basket. The request itself is
 * the problem.
 */
public class EmptyCartException extends RuntimeException {

    public EmptyCartException() {
        super("Cart is empty");
    }
}
