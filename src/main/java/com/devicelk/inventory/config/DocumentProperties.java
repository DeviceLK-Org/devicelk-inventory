package com.devicelk.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code app.documents.*}: where product spec documents are stored,
 * and which Bedrock knowledge base indexes them.
 * <p>
 * Note what is <b>not</b> here — credentials. They are resolved by the AWS
 * default provider chain, so this record holds only non-secret coordinates and
 * the whole block is safe in version control.
 * <p>
 * The bucket and the knowledge base are two separate things on purpose: writing
 * an object to {@link #bucket()} does not put it in the knowledge base. Only an
 * ingestion job against {@link #dataSourceId()} does that.
 */
@ConfigurationProperties(prefix = "app.documents")
public record DocumentProperties(
        String bucket,
        String knowledgeBaseId,
        String dataSourceId,
        String region
) {
}
