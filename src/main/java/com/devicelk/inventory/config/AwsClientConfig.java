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
 * — environment variables in dev, an instance role when deployed. That is why no
 * secret appears in this service's configuration.
 * <p>
 * Both clients are synchronous: uploads are kilobytes, and ingestion calls return
 * once the job is accepted rather than finished.
 * <p>
 * Both name {@link UrlConnectionHttpClient} explicitly because the SDK default is
 * Apache HttpClient 5, and this SDK version needs a newer httpclient5 than Spring
 * Boot manages — see the exclusion in {@code pom.xml}.
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
     * Bedrock control plane — ingestion jobs and document listing. Distinct from
     * the {@code BedrockAgentRuntimeClient} DeviceLK-AIRetrieval uses for
     * queries: neither client can do the other's job.
     */
    @Bean
    BedrockAgentClient bedrockAgentClient(DocumentProperties properties) {
        return BedrockAgentClient.builder()
                .region(Region.of(properties.region()))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
