package com.devicelk.cart.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /api/v1/cart/items}.
 * <p>
 * Note what is <b>not</b> here: any notion of whose cart this is. The owner
 * comes from the validated JWT and nowhere else, so there is no field a caller
 * could set to operate on somebody else's basket.
 *
 * @param productId the product to add
 * @param quantity  units to add; must be positive — "add zero of something" is
 *                  a malformed request rather than an instruction
 */
public record AddItemRequest(

        @NotNull(message = "Product id is required")
        Long productId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be greater than zero")
        Integer quantity
) {
}
