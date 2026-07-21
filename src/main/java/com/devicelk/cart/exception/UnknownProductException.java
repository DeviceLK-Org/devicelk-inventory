package com.devicelk.cart.exception;

/**
 * Raised when a cart operation names a product that inventory does not have.
 * <p>
 * Distinct from the inventory module's own not-found exception on purpose: that
 * type is internal to inventory, so catching it here would mean reaching across
 * a module boundary. {@code InventoryFacade} reports absence as an empty
 * {@code Optional} and this is what the cart module turns that into.
 * <p>
 * Maps to HTTP 404.
 */
public class UnknownProductException extends RuntimeException {

    public UnknownProductException(Long productId) {
        super("Product " + productId + " does not exist.");
    }
}
