package com.devicelk.inventory.api;

import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.domain.Stock;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body accepted by {@code POST /inventory} and {@code PUT /inventory/{id}}.
 * <p>
 * Keeps the {@link Product} entity out of the HTTP layer, so storage decisions do
 * not leak into the public contract — the move to integer cents and the split of
 * stock into its own table would both otherwise have changed the accepted fields.
 * This DTO holds the wire shape steady while persistence changes underneath.
 *
 * @param price             major units (e.g. {@code 42999.99}); converted to the
 *                          entity's minor-unit {@code priceCents} by the service
 * @param stockQuantity     units on hand, written to {@link Stock#getAvailableQty()}
 * @param minStockThreshold re-order trigger level, written to {@link Stock}
 */
public record ProductWriteRequest(

        @NotBlank(message = "Product name is required")
        String name,

        @NotBlank(message = "Brand is required")
        String brand,

        @NotNull(message = "Category is required")
        Category category,

        @NotNull(message = "Price is required")
        @Positive(message = "Price must be greater than zero")
        // Guards Money.toCents, which refuses to round: a third decimal has no
        // representation in cents.
        @Digits(integer = 10, fraction = 2,
                message = "Price must have at most 2 decimal places")
        BigDecimal price,

        @NotNull(message = "Stock quantity is required")
        @Min(value = 0, message = "Stock quantity cannot be negative")
        Integer stockQuantity,

        @NotNull(message = "Minimum stock threshold is required")
        @Min(value = 0, message = "Minimum stock threshold cannot be negative")
        Integer minStockThreshold,

        @Size(max = 1000, message = "Description cannot exceed 1000 characters")
        String description
) {
}
