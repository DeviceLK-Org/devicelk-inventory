package com.devicelk.inventory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS clients backing the product spec-document feature.
 * <p>
 * Neither builder names a credentials provider, so the SDK default chain applies
 * — environment variables in dev, an instance role in a deployed environment.
 * That is the whole reason no secret appears in this service's configuration.
 * <p>
 * Both clients are synchronous. Uploads are small (kilobytes) and the ingestion
 * calls return as soon as the job is <em>accepted</em> rather than finished, so
 * nothing here blocks long enough to justify the async variants.
 * <p>
 * Both also name {@link UrlConnectionHttpClient} explicitly rather than letting
 * the SDK pick its default. The default is Apache HttpClient 5, and this SDK
 * version needs a newer httpclient5 than Spring Boot manages — see the exclusion
 * comment in {@code pom.xml}. Naming the transport here keeps that decision
 * visible at the point where the clients are actually built.
 */
@Configuration
@EnableConfigurationProperties(DocumentProperties.class)
public class AwsClientConfig {

    /** Object storage for the {@code .md} spec sheets themselves. */
    @Bean
    S3Client s3Client(DocumentProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    /**
     * Bedrock <b>control</b> plane — ingestion jobs and document listing.
     * <p>
     * Not to be confused with {@code BedrockAgentRuntimeClient}, which is what
     * DeviceLK-AIRetrieval uses to run queries. The runtime client cannot start
     * an ingestion job, and this one cannot answer a question.
     */
    @Bean
    BedrockAgentClient bedrockAgentClient(DocumentProperties properties) {
        return BedrockAgentClient.builder()
                .region(Region.of(properties.region()))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
