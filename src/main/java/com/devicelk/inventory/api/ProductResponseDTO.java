package com.devicelk.inventory.api;

import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;

import java.math.BigDecimal;

/**
 * Product data as returned to API clients.
 * <p>
 * A DTO rather than the {@link Product} entity, so persistence details and
 * lazy-loading never leak into the public API contract.
 */
public record ProductResponseDTO(
        Long id,
        String name,
        String brand,
        Category category,
        BigDecimal price,
        Integer stockQuantity,
        Integer minStockThreshold,
        String description,
        /**
         * S3 key of the product's spec document, or {@code null} if it has none.
         * The admin portal uses its presence to show a document indicator; it does
         * not imply the knowledge base has indexed the document.
         */
        String documentKey
) {
}
