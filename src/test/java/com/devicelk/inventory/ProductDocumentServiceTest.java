package com.devicelk.inventory;

import com.devicelk.inventory.api.ProductResponseDTO;
import com.devicelk.inventory.config.DocumentProperties;
import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.exception.InvalidDocumentException;
import com.devicelk.inventory.exception.ProductNotFoundException;
import com.devicelk.inventory.repository.ProductRepository;
import com.devicelk.inventory.service.ProductDocumentService;
import com.devicelk.inventory.service.ProductDocumentServiceFactory;
import com.devicelk.inventory.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.bedrockagent.BedrockAgentClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for spec-document upload.
 * <p>
 * Both AWS clients are mocked: nothing here makes a network call, so the suite
 * runs without credentials and without touching the real bucket.
 */
class ProductDocumentServiceTest {

    private static final byte[] CONTENT = "# Specs".getBytes(StandardCharsets.UTF_8);

    private S3Client s3;
    private ProductRepository products;
    private ProductService productService;
    private ProductDocumentService service;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        products = mock(ProductRepository.class);
        productService = mock(ProductService.class);
        service = ProductDocumentServiceFactory.create(
                s3, mock(BedrockAgentClient.class), products, productService,
                new DocumentProperties("test-bucket", "KB1", "DS1", "us-east-1"));
    }

    private Product product(long id, String documentKey) {
        Product p = new Product("MacBook Pro", "Apple", Category.LAPTOP,
                89999900L, "LKR", "desc");
        p.setId(id);
        p.setDocumentKey(documentKey);
        when(products.findById(id)).thenReturn(Optional.of(p));
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
        // The response comes from the existing product mapper, which owns the
        // money conversion and the stock lookup — it is not rebuilt here.
        when(productService.getProductById(id)).thenReturn(new ProductResponseDTO(
                id, p.getName(), p.getBrand(), p.getCategory(),
                new BigDecimal("899999.00"), 5, 1, p.getDescription(), documentKey));
        return p;
    }

    @Test
    void uploadsUnderProductPrefixedKey() {
        product(7L, null);

        service.uploadDocument(7L, "MacBook_Pro_Specs.md", CONTENT);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(put.getValue().key()).isEqualTo("product-7/MacBook_Pro_Specs.md");
        assertThat(put.getValue().contentType()).isEqualTo("text/markdown");
    }

    @Test
    void persistsTheKeyOnTheProduct() {
        Product p = product(7L, null);

        service.uploadDocument(7L, "Specs.md", CONTENT);

        assertThat(p.getDocumentKey()).isEqualTo("product-7/Specs.md");
        verify(products).save(p);
    }

    @Test
    void rejectsNonMarkdownFilename() {
        product(7L, null);

        assertThatThrownBy(() -> service.uploadDocument(7L, "specs.pdf", CONTENT))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining(".md");

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void rejectsEmptyFile() {
        product(7L, null);

        assertThatThrownBy(() -> service.uploadDocument(7L, "specs.md", new byte[0]))
                .isInstanceOf(InvalidDocumentException.class);

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void stripsPathSeparatorsSoUploadsCannotEscapeTheirPrefix() {
        product(7L, null);

        service.uploadDocument(7L, "../../etc/evil.md", CONTENT);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).isEqualTo("product-7/evil.md");
    }

    @Test
    void replacingUnderADifferentNameDeletesTheOldObject() {
        product(7L, "product-7/Old_Specs.md");

        service.uploadDocument(7L, "New_Specs.md", CONTENT);

        ArgumentCaptor<DeleteObjectRequest> del = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(del.capture());
        assertThat(del.getValue().key()).isEqualTo("product-7/Old_Specs.md");
    }

    @Test
    void replacingUnderTheSameNameDoesNotDeleteWhatItJustWrote() {
        product(7L, "product-7/Specs.md");

        service.uploadDocument(7L, "Specs.md", CONTENT);

        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void unknownProductIsNotFound() {
        when(products.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.uploadDocument(99L, "specs.md", CONTENT))
                .isInstanceOf(ProductNotFoundException.class);

        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
