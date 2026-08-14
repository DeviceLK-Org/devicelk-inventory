package com.devicelk.inventory;

import com.devicelk.inventory.config.DocumentProperties;
import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.exception.InvalidDocumentException;
import com.devicelk.inventory.repository.ProductRepository;
import com.devicelk.inventory.service.ProductDocumentService;
import com.devicelk.inventory.service.ProductDocumentServiceFactory;
import com.devicelk.inventory.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductDocumentDeleteTest {

    private S3Client s3;
    private ProductRepository products;
    private ProductDocumentService service;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        products = mock(ProductRepository.class);
        service = ProductDocumentServiceFactory.create(s3, mock(BedrockAgentClient.class),
                products, mock(ProductService.class),
                new DocumentProperties("test-bucket", "KB1", "DS1", "us-east-1"));
        when(products.findByDocumentKey(any())).thenReturn(Optional.empty());
    }

    @Test
    void deletesTheObjectFromTheConfiguredBucket() {
        service.deleteDocument("product-7/Specs.md");

        ArgumentCaptor<DeleteObjectRequest> del = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(del.capture());
        assertThat(del.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(del.getValue().key()).isEqualTo("product-7/Specs.md");
    }

    /**
     * Without this the product keeps pointing at a file that no longer exists and
     * the Products page keeps showing a document indicator for it.
     */
    @Test
    void clearsTheReferenceOnTheOwningProduct() {
        Product p = new Product("MacBook Pro", "Apple", Category.LAPTOP, 1L, "LKR", null);
        p.setId(7L);
        p.setDocumentKey("product-7/Specs.md");
        when(products.findByDocumentKey("product-7/Specs.md")).thenReturn(Optional.of(p));

        service.deleteDocument("product-7/Specs.md");

        assertThat(p.getDocumentKey()).isNull();
        verify(products).save(p);
    }

    @Test
    void deletingAnUnlinkedLegacyFileTouchesNoProduct() {
        service.deleteDocument("MacBook_Neo_Specs.md");

        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        verify(products, never()).save(any(Product.class));
    }

    @Test
    void rejectsATraversalKey() {
        assertThatThrownBy(() -> service.deleteDocument("../secrets.md"))
                .isInstanceOf(InvalidDocumentException.class);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void rejectsABlankKey() {
        assertThatThrownBy(() -> service.deleteDocument("  "))
                .isInstanceOf(InvalidDocumentException.class);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void rejectsANullKey() {
        assertThatThrownBy(() -> service.deleteDocument(null))
                .isInstanceOf(InvalidDocumentException.class);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
