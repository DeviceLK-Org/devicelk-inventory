package com.devicelk.inventory.exception;

/**
 * A knowledge-base ingestion job is already running.
 * <p>
 * Bedrock permits only one job at a time per data source. Starting a second is
 * an ordinary outcome rather than a fault — the documents waiting to be indexed
 * will be picked up by the job already in flight — so this maps to <b>409</b>
 * and the portal reports it as information, not as an error.
 */
public class SyncInProgressException extends RuntimeException {

    public SyncInProgressException(String message) {
        super(message);
    }
}
