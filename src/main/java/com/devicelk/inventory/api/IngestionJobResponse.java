package com.devicelk.inventory.api;

/**
 * A Bedrock knowledge-base ingestion job, as reported to the admin portal.
 * <p>
 * {@code status} is passed through as Bedrock's own string (STARTING,
 * IN_PROGRESS, COMPLETE, FAILED) rather than being remapped: the portal polls
 * until it reads COMPLETE or FAILED, and inventing a parallel vocabulary here
 * would only add a translation to get wrong.
 */
public record IngestionJobResponse(String ingestionJobId, String status) {
}
