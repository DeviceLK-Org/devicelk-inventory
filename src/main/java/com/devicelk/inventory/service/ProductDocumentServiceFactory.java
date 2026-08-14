package com.devicelk.inventory.service;

import com.devicelk.inventory.config.DocumentProperties;
import com.devicelk.inventory.repository.ProductRepository;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds a {@link ProductDocumentService} without making the implementation
 * public.
 * <p>
 * {@code ProductDocumentServiceImpl} is package-private, so a test in another
 * package cannot call its constructor. Widening the class to public purely for
 * testing would leak an internal type into the module's API; this narrow factory
 * keeps the implementation hidden while letting tests construct one with mocked
 * AWS clients.
 */
public final class ProductDocumentServiceFactory {

    private ProductDocumentServiceFactory() {
    }

    public static ProductDocumentService create(S3Client s3,
                                                BedrockAgentClient bedrock,
                                                ProductRepository products,
                                                ProductService productService,
                                                DocumentProperties properties) {
        return new ProductDocumentServiceImpl(s3, bedrock, products, productService, properties);
    }
}
