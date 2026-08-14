package com.devicelk.inventory.api;

import java.time.Instant;

/**
 * One row of the admin portal's Knowledge base page.
 *
 * @param key          the S3 object key, e.g. {@code product-7/Specs.md}
 * @param sizeBytes    {@code null} when the state is {@link DocumentState#PENDING_REMOVAL} —
 *                     the S3 object it would come from no longer exists
 * @param lastModified {@code null} for the same reason
 * @param productId    owning product, or {@code null} for the legacy documents at
 *                     the bucket root that belong to no product
 * @param productName  owning product's name, or {@code null} as above
 * @param state        whether the AI can currently see this document
 */
public record DocumentSummary(
        String key,
        Long sizeBytes,
        Instant lastModified,
        Long productId,
        String productName,
        DocumentState state
) {
}
