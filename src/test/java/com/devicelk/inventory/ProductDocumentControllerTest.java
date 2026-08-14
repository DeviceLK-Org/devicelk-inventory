package com.devicelk.inventory;

import com.devicelk.inventory.api.IngestionJobResponse;
import com.devicelk.inventory.api.ProductDocumentController;
import com.devicelk.inventory.exception.DocumentStorageException;
import com.devicelk.inventory.exception.GlobalExceptionHandler;
import com.devicelk.inventory.exception.InvalidDocumentException;
import com.devicelk.inventory.exception.ProductNotFoundException;
import com.devicelk.inventory.exception.SyncInProgressException;
import com.devicelk.inventory.service.ProductDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract the admin portal branches on.
 * <p>
 * The service tests cover the logic; these cover the status codes, which is
 * where the two subtle traps live: a validation failure must not surface as the
 * 409 that {@code GlobalExceptionHandler} gives {@code IllegalArgumentException},
 * and a document key containing {@code /} must survive the round trip.
 */
// SecurityConfig is imported, not stubbed out. @WebMvcTest does not pick up the
// application's own security configuration, so without it Spring Security's
// defaults apply and every POST/DELETE here returns 403 on CSRF -- a failure the
// real service never produces, because SecurityConfig disables CSRF for exactly
// this reason. Importing it makes the slice match production instead of testing
// a configuration that does not exist anywhere.
@WebMvcTest(ProductDocumentController.class)
@Import({GlobalExceptionHandler.class, com.devicelk.SecurityConfig.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class ProductDocumentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProductDocumentService documentService;

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "Specs.md", "text/markdown",
                "# Specs".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadReturnsTheUpdatedProduct() throws Exception {
        when(documentService.uploadDocument(eq(7L), any(), any()))
                .thenReturn(new com.devicelk.inventory.api.ProductResponseDTO(
                        7L, "MacBook Pro", "Apple",
                        com.devicelk.inventory.domain.Category.LAPTOP,
                        new java.math.BigDecimal("899999.00"), 5, 1, "desc",
                        "product-7/Specs.md"));

        mvc.perform(multipart("/inventory/7/document").file(file()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentKey").value("product-7/Specs.md"));
    }

    @Test
    void unknownProductIs404() throws Exception {
        when(documentService.uploadDocument(eq(99L), any(), any()))
                .thenThrow(new ProductNotFoundException(99L));

        mvc.perform(multipart("/inventory/99/document").file(file()))
                .andExpect(status().isNotFound());
    }

    /** The trap: a validation failure must be 400, not the 409 that
     *  IllegalArgumentException would have produced. */
    @Test
    void invalidDocumentIs400NotConflict() throws Exception {
        when(documentService.uploadDocument(any(), any(), any()))
                .thenThrow(new InvalidDocumentException("must be a .md file"));

        mvc.perform(multipart("/inventory/7/document").file(file()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAwsFailureIs502NotAnInternalError() throws Exception {
        when(documentService.uploadDocument(any(), any(), any()))
                .thenThrow(new DocumentStorageException("S3 refused", new RuntimeException()));

        mvc.perform(multipart("/inventory/7/document").file(file()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void syncReturnsTheJobId() throws Exception {
        when(documentService.startSync()).thenReturn(new IngestionJobResponse("JOB1", "STARTING"));

        mvc.perform(post("/inventory/documents/sync"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ingestionJobId").value("JOB1"));
    }

    @Test
    void syncAlreadyRunningIs409() throws Exception {
        when(documentService.startSync())
                .thenThrow(new SyncInProgressException("already running"));

        mvc.perform(post("/inventory/documents/sync"))
                .andExpect(status().isConflict());
    }

    /** Keys contain '/', which is why the key travels as a query parameter. */
    @Test
    void deleteKeyWithASlashArrivesIntact() throws Exception {
        doNothing().when(documentService).deleteDocument(any());

        mvc.perform(delete("/inventory/documents").param("key", "product-7/My Specs.md"))
                .andExpect(status().isNoContent());

        verify(documentService).deleteDocument("product-7/My Specs.md");
    }

    @Test
    void deleteOfAnInvalidKeyIs400() throws Exception {
        doThrow(new InvalidDocumentException("bad key"))
                .when(documentService).deleteDocument(any());

        mvc.perform(delete("/inventory/documents").param("key", "../evil.md"))
                .andExpect(status().isBadRequest());
    }
}
