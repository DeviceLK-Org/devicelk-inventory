package com.devicelk.productservice.exception;

/**
 * Thrown by the service layer when a requested {@code Product} does not exist.
 * Translated into an HTTP 404 response by {@link GlobalExceptionHandler}.
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("Product not found with id: " + id);
    }
}
