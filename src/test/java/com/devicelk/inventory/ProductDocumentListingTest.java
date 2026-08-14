package com.devicelk.inventory;

import com.devicelk.inventory.api.DocumentState;
import com.devicelk.inventory.api.DocumentSummary;
import com.devicelk.inventory.config.DocumentProperties;
import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.repository.ProductRepository;
import com.devicelk.inventory.service.ProductDocumentService;
import com.devicelk.inventory.service.ProductDocumentServiceFactory;
import com.devicelk.inventory.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.bedrockagent.model.DocumentIdentifier;
import software.amazon.awssdk.services.bedrockagent.model.KnowledgeBaseDocumentDetail;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsRequest;
import software.amazon.awssdk.services.bedrockagent.model.ListKnowledgeBaseDocumentsResponse;
import software.amazon.awssdk.services.bedrockagent.model.S3Location;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The listing is the <b>union</b> of the bucket and the knowledge base.
 * <p>
 * Without the union, a document deleted from S3 but still indexed would vanish
 * from the portal while the AI kept answering from it — the exact silent drift
 * this whole feature exists to make visible.
 */
class ProductDocumentListingTest {

    private S3Client s3;
    private BedrockAgentClient bedrock;
    private ProductRepository products;
    private ProductDocumentService service;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        bedrock = mock(BedrockAgentClient.class);
        products = mock(ProductRepository.class);
        service = ProductDocumentServiceFactory.create(s3, bedrock, products,
                mock(ProductService.class),
                new DocumentProperties("test-bucket", "KB1", "DS1", "us-east-1"));
        when(products.findByDocumentKeyIn(any())).thenReturn(List.of());
        bucketHas();
        knowledgeBaseHas();
    }

    private void bucketHas(String... keys) {
        List<S3Object> objects = Arrays.stream(keys)
                .map(k -> S3Object.builder().key(k).size(100L)
                        .lastModified(Instant.parse("2026-08-15T00:00:00Z")).build())
                .toList();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(objects).isTruncated(false).build());
    }

    private void knowledgeBaseHas(String... keys) {
        List<KnowledgeBaseDocumentDetail> docs = Arrays.stream(keys)
                .map(k -> KnowledgeBaseDocumentDetail.builder()
                        .identifier(DocumentIdentifier.builder()
                                .s3(S3Location.builder().uri("s3://test-bucket/" + k).build())
                                .build())
                        .build())
                .toList();
        when(bedrock.listKnowledgeBaseDocuments(any(ListKnowledgeBaseDocumentsRequest.class)))
                .thenReturn(ListKnowledgeBaseDocumentsResponse.builder()
                        .documentDetails(docs).build());
    }

    @Test
    void inBucketOnlyIsPendingIngest() {
        bucketHas("product-7/New.md");

        assertThat(service.listDocuments())
                .extracting(DocumentSummary::key, DocumentSummary::state)
                .containsExactly(tuple("product-7/New.md", DocumentState.PENDING_INGEST));
    }

    @Test
    void inBothIsIndexed() {
        bucketHas("Legacy.md");
        knowledgeBaseHas("Legacy.md");

        assertThat(service.listDocuments())
                .extracting(DocumentSummary::key, DocumentSummary::state)
                .containsExactly(tuple("Legacy.md", DocumentState.INDEXED));
    }

    @Test
    void inKnowledgeBaseOnlyIsPendingRemoval() {
        knowledgeBaseHas("Deleted.md");

        assertThat(service.listDocuments())
                .extracting(DocumentSummary::key, DocumentSummary::state,
                        DocumentSummary::sizeBytes, DocumentSummary::lastModified)
                .containsExactly(tuple("Deleted.md", DocumentState.PENDING_REMOVAL, null, null));
    }

    @Test
    void linksRowsBackToTheirProduct() {
        bucketHas("product-7/Specs.md");
        knowledgeBaseHas("product-7/Specs.md");
        Product p = new Product("MacBook Pro", "Apple", Category.LAPTOP, 1L, "LKR", null);
        p.setId(7L);
        p.setDocumentKey("product-7/Specs.md");
        when(products.findByDocumentKeyIn(any())).thenReturn(List.of(p));

        assertThat(service.listDocuments())
                .extracting(DocumentSummary::productId, DocumentSummary::productName)
                .containsExactly(tuple(7L, "MacBook Pro"));
    }

    @Test
    void unlinkedLegacyFilesHaveNoProduct() {
        bucketHas("MacBook_Neo_Specs.md");
        knowledgeBaseHas("MacBook_Neo_Specs.md");

        assertThat(service.listDocuments())
                .extracting(DocumentSummary::productId)
                .containsExactly((Long) null);
    }

    @Test
    void reportsAllThreeStatesTogether() {
        bucketHas("Indexed.md", "New.md");
        knowledgeBaseHas("Indexed.md", "Gone.md");

        assertThat(service.listDocuments())
                .extracting(DocumentSummary::key, DocumentSummary::state)
                .containsExactlyInAnyOrder(
                        tuple("Indexed.md", DocumentState.INDEXED),
                        tuple("New.md", DocumentState.PENDING_INGEST),
                        tuple("Gone.md", DocumentState.PENDING_REMOVAL));
    }

    /** A KB entry pointing at some other bucket must not be mistaken for ours. */
    @Test
    void ignoresKnowledgeBaseEntriesFromAnotherBucket() {
        knowledgeBaseHas();
        when(bedrock.listKnowledgeBaseDocuments(any(ListKnowledgeBaseDocumentsRequest.class)))
                .thenReturn(ListKnowledgeBaseDocumentsResponse.builder()
                        .documentDetails(List.of(KnowledgeBaseDocumentDetail.builder()
                                .identifier(DocumentIdentifier.builder()
                                        .s3(S3Location.builder()
                                                .uri("s3://some-other-bucket/Foreign.md").build())
                                        .build())
                                .build()))
                        .build());

        assertThat(service.listDocuments()).isEmpty();
    }
}
