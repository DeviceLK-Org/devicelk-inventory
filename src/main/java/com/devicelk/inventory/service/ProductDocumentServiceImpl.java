package com.devicelk.inventory.service;

import com.devicelk.inventory.api.ProductResponseDTO;
import com.devicelk.inventory.config.DocumentProperties;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.exception.DocumentStorageException;
import com.devicelk.inventory.exception.InvalidDocumentException;
import com.devicelk.inventory.exception.ProductNotFoundException;
import com.devicelk.inventory.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Default {@link ProductDocumentService}.
 * <p>
 * Package-private, matching this module's convention that only the interface is
 * visible outside the package. Constructed through
 * {@link ProductDocumentServiceFactory} where a caller in another package needs
 * one (notably tests).
 */
@Service
class ProductDocumentServiceImpl implements ProductDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ProductDocumentServiceImpl.class);

    private static final String MARKDOWN_SUFFIX = ".md";
    private static final String MARKDOWN_CONTENT_TYPE = "text/markdown";

    private final S3Client s3;
    private final BedrockAgentClient bedrock;
    private final ProductRepository products;
    private final ProductService productService;
    private final DocumentProperties properties;

    ProductDocumentServiceImpl(S3Client s3,
                               BedrockAgentClient bedrock,
                               ProductRepository products,
                               ProductService productService,
                               DocumentProperties properties) {
        this.s3 = s3;
        this.bedrock = bedrock;
        this.products = products;
        this.productService = productService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public ProductResponseDTO uploadDocument(Long productId, String originalFilename, byte[] content) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        String filename = sanitize(originalFilename);
        if (content == null || content.length == 0) {
            throw new InvalidDocumentException("The uploaded document is empty.");
        }

        String previousKey = product.getDocumentKey();
        String key = "product-" + productId + "/" + filename;

        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(MARKDOWN_CONTENT_TYPE)
                    .build(), RequestBody.fromBytes(content));

            // Only after the write succeeds, and only when the name actually
            // changed. Deleting a key equal to the one just written would erase
            // the document this call was meant to store.
            if (previousKey != null && !previousKey.equals(key)) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(previousKey)
                        .build());
            }
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not store the document in S3.", e);
        }

        product.setDocumentKey(key);
        products.save(product);

        log.info("Stored spec document {} for product {}. It stays invisible to the "
                + "knowledge base until an ingestion job runs.", key, productId);

        // Reuse the existing product mapper rather than rebuilding the DTO: it
        // owns the cents-to-BigDecimal money conversion and the stock lookup,
        // and duplicating either here would fork the one conversion this
        // codebase is most careful to keep in a single place.
        return productService.getProductById(productId);
    }

    /**
     * Reduces a client-supplied filename to a bare, safe leaf name.
     * <p>
     * Path separators are <em>stripped</em> rather than merely rejected, so an
     * upload cannot escape its {@code product-{id}/} prefix no matter what the
     * client sends. A remaining {@code ..} is then refused outright.
     */
    private String sanitize(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidDocumentException("The document must have a filename.");
        }
        String normalized = originalFilename.replace('\\', '/');
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1).trim();

        if (leaf.isEmpty() || leaf.equals(".") || leaf.contains("..")) {
            throw new InvalidDocumentException("The document filename is not valid.");
        }
        if (!leaf.toLowerCase().endsWith(MARKDOWN_SUFFIX)) {
            throw new InvalidDocumentException(
                    "The spec document must be a " + MARKDOWN_SUFFIX + " file.");
        }
        return leaf;
    }
}
