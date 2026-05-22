package com.devicelk.dto;

import com.devicelk.domain.Category;
import com.devicelk.domain.Product;

import java.math.BigDecimal;

/**
 * Immutable data transfer object used to return product data to API clients.
 * <p>
 * Implemented as a Java {@code record}: it is immutable, generates the
 * constructor, accessors, {@code equals}/{@code hashCode}/{@code toString}
 * automatically, and decouples the public API contract from the internal
 * {@link Product} JPA entity. Exposing a
 * DTO instead of the entity prevents accidental leakage of persistence
 * details and lazy-loading issues.
 */
public record ProductResponseDTO(
        Long id,
        String name,
        String brand,
        Category category,
        BigDecimal price,
        Integer stockQuantity,
        String description
) {
}
