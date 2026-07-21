package com.devicelk.cart.api;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/v1/cart/items/{itemId}}.
 * <p>
 * Deliberately <b>not</b> annotated {@code @Positive}, unlike
 * {@link AddItemRequest}. Setting a line to zero is a meaningful instruction —
 * "I no longer want this" — and the service honours it by removing the line.
 * Rejecting it with a 400 would answer a coherent request with a complaint and
 * force the client into a second call it should not need to know about.
 *
 * @param quantity the absolute quantity wanted; zero or less removes the line
 */
public record UpdateQuantityRequest(

        @NotNull(message = "Quantity is required")
        Integer quantity
) {
}
