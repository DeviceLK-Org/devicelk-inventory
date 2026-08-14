package com.devicelk.inventory.api;

/**
 * Where a spec document stands between S3 storage and the Bedrock knowledge base.
 * <p>
 * Derived from set membership each time the listing is built; never stored. The
 * two "pending" states exist because the bucket and the knowledge base drift
 * apart between ingestion jobs, and an admin who cannot see that drift has no
 * way to explain why the AI does or does not know about a document.
 */
public enum DocumentState {

    /** In the bucket, not in the knowledge base — the AI cannot see it yet. */
    PENDING_INGEST,

    /** In both. Live and answerable. */
    INDEXED,

    /**
     * Deleted from the bucket but still in the knowledge base — the AI can
     * <em>still</em> answer from it until the next ingestion job removes it.
     */
    PENDING_REMOVAL
}
