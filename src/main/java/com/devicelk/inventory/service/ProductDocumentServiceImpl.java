package com.devicelk.inventory.service;

import com.devicelk.inventory.api.DocumentState;
import com.devicelk.inventory.api.DocumentSummary;
import com.devicelk.inventory.api.IngestionJobResponse;
import com.devicelk.inventory.api.ProductResponseDTO;
import com.devicelk.inventory.config.DocumentProperties;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.exception.DocumentStorageException;
import com.devicelk.inventory.exception.InvalidDocumentException;
import com.devicelk.inventory.exception.ProductNotFoundException;
import com.devicelk.inventory.exception.SyncInProgressException;
import com.devicelk.inventory.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.ConflictException;
import software.amazon.awssdk.services.bedrockagent.model.GetIngestionJobRequest;
import software.amazon.awssdk.services.bedrockagent.model.IngestionJob;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsResponse;
import software.amazon.awssdk.services.bedrockagent.model.StartIngestionJobRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

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

    @Override
    @Transactional
    public void deleteDocument(String key) {
        if (key == null || key.isBlank() || key.contains("..")) {
            throw new InvalidDocumentException("The document key is not valid.");
        }

        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not delete the document from S3.", e);
        }

        // Legacy documents at the bucket root belong to no product, so an empty
        // result here is normal rather than an inconsistency.
        products.findByDocumentKey(key).ifPresent(product -> {
            product.setDocumentKey(null);
            products.save(product);
        });

        log.info("Deleted spec document {}. The knowledge base can still answer "
                + "from it until the next ingestion job runs.", key);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentSummary> listDocuments() {
        Map<String, S3Object> inBucket = listBucketObjects();
        Set<String> inKnowledgeBase = listIndexedKeys();

        // TreeSet so the page has a stable, alphabetical order without the
        // caller needing to sort.
        Set<String> allKeys = new TreeSet<>(inBucket.keySet());
        allKeys.addAll(inKnowledgeBase);

        Map<String, Product> byKey = products.findByDocumentKeyIn(allKeys).stream()
                .collect(Collectors.toMap(Product::getDocumentKey, p -> p, (a, b) -> a));

        List<DocumentSummary> summaries = new ArrayList<>(allKeys.size());
        for (String key : allKeys) {
            S3Object object = inBucket.get(key);
            boolean indexed = inKnowledgeBase.contains(key);
            DocumentState state = object == null ? DocumentState.PENDING_REMOVAL
                    : indexed ? DocumentState.INDEXED
                    : DocumentState.PENDING_INGEST;
            Product product = byKey.get(key);
            summaries.add(new DocumentSummary(
                    key,
                    object == null ? null : object.size(),
                    object == null ? null : object.lastModified(),
                    product == null ? null : product.getId(),
                    product == null ? null : product.getName(),
                    state));
        }
        return summaries;
    }

    /**
     * Every object in the bucket, keyed by object key.
     * <p>
     * Pages explicitly rather than assuming a single response: the bucket holds
     * ten objects today, but a listing that silently truncates would report
     * documents as missing rather than failing, which is the worse outcome.
     */
    private Map<String, S3Object> listBucketObjects() {
        Map<String, S3Object> objects = new LinkedHashMap<>();
        String token = null;
        try {
            do {
                ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(properties.bucket())
                        .continuationToken(token)
                        .build());
                response.contents().forEach(o -> objects.put(o.key(), o));
                token = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not list documents in S3.", e);
        }
        return objects;
    }

    /**
     * Keys the knowledge base currently has indexed.
     * <p>
     * Bedrock reports absolute {@code s3://bucket/key} URIs, so they are reduced
     * to bare keys. Entries from any other bucket are filtered out rather than
     * assumed to be ours — a knowledge base can have more than one source.
     */
    private Set<String> listIndexedKeys() {
        String prefix = "s3://" + properties.bucket() + "/";
        Set<String> keys = new HashSet<>();
        String token = null;
        try {
            do {
                ListKnowledgeBaseDocumentsResponse response =
                        bedrock.listKnowledgeBaseDocuments(ListKnowledgeBaseDocumentsRequest.builder()
                                .knowledgeBaseId(properties.knowledgeBaseId())
                                .dataSourceId(properties.dataSourceId())
                                .nextToken(token)
                                .build());
                response.documentDetails().stream()
                        .map(d -> d.identifier().s3().uri())
                        .filter(uri -> uri.startsWith(prefix))
                        .map(uri -> uri.substring(prefix.length()))
                        .forEach(keys::add);
                token = response.nextToken();
            } while (token != null);
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not list knowledge base documents.", e);
        }
        return keys;
    }

    @Override
    public IngestionJobResponse startSync() {
        try {
            IngestionJob job = bedrock.startIngestionJob(StartIngestionJobRequest.builder()
                    .knowledgeBaseId(properties.knowledgeBaseId())
                    .dataSourceId(properties.dataSourceId())
                    .build()).ingestionJob();
            log.info("Started knowledge base ingestion job {}", job.ingestionJobId());
            return toResponse(job);
        } catch (ConflictException e) {
            // Not a fault: Bedrock allows one job per data source at a time, and
            // the running job will pick up whatever is waiting anyway.
            throw new SyncInProgressException("A knowledge base sync is already running.");
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not start the knowledge base sync.", e);
        }
    }

    @Override
    public IngestionJobResponse getSyncStatus(String jobId) {
        try {
            return toResponse(bedrock.getIngestionJob(GetIngestionJobRequest.builder()
                    .knowledgeBaseId(properties.knowledgeBaseId())
                    .dataSourceId(properties.dataSourceId())
                    .ingestionJobId(jobId)
                    .build()).ingestionJob());
        } catch (SdkException e) {
            throw new DocumentStorageException("Could not read the sync status.", e);
        }
    }

    private IngestionJobResponse toResponse(IngestionJob job) {
        return new IngestionJobResponse(job.ingestionJobId(), job.statusAsString());
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
