package com.devicelk.inventory.api;

import com.devicelk.inventory.exception.InvalidDocumentException;
import com.devicelk.inventory.service.ProductDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Spec-document endpoints, rooted at {@code /inventory} alongside
 * {@link ProductController}.
 * <p>
 * A thin adapter, in keeping with the rest of this layer: it turns the multipart
 * upload into plain bytes and delegates. No business logic lives here.
 * <p>
 * Protected at the API gateway rather than here, exactly like the other
 * {@code /inventory/**} writes — see {@code SecurityConfig} for why this
 * service's own port is left open.
 */
@RestController
@RequestMapping("/inventory")
public class ProductDocumentController {

    private final ProductDocumentService documentService;

    public ProductDocumentController(ProductDocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Attaches a {@code .md} spec sheet to a product, replacing any existing one.
     * <p>
     * Size is capped by {@code spring.servlet.multipart.max-file-size}, which
     * rejects an oversize upload before this method is entered.
     *
     * @return HTTP 200 with the product, now carrying its {@code documentKey}
     */
    @PostMapping("/{id}/document")
    public ResponseEntity<ProductResponseDTO> upload(@PathVariable Long id,
                                                     @RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidDocumentException("The uploaded document could not be read.");
        }
        return ResponseEntity.ok(
                documentService.uploadDocument(id, file.getOriginalFilename(), content));
    }

    /**
     * Removes a spec document from storage.
     * <p>
     * The key travels as a <b>query parameter</b> rather than a path variable:
     * keys contain {@code /} (e.g. {@code product-7/Specs.md}), which a path
     * variable cannot carry without the double-encoding Spring rejects by
     * default.
     * <p>
     * 204 means the object is gone from storage. It does <em>not</em> mean the
     * AI has stopped answering from it — that happens on the next sync.
     */
    @DeleteMapping("/documents")
    public ResponseEntity<Void> delete(@RequestParam("key") String key) {
        documentService.deleteDocument(key);
        return ResponseEntity.noContent().build();
    }

    /**
     * Every known spec document, with its knowledge-base state.
     * <p>
     * Backs the portal's Knowledge base page. Includes documents that exist only
     * in the knowledge base — see {@code DocumentState.PENDING_REMOVAL}.
     */
    @GetMapping("/documents")
    public ResponseEntity<List<DocumentSummary>> list() {
        return ResponseEntity.ok(documentService.listDocuments());
    }

    /**
     * Starts a knowledge-base sync for the whole data source.
     * <p>
     * 202 Accepted, not 200: the job has been queued, not finished. A job
     * already in flight comes back as 409 via
     * {@code SyncInProgressException} — expected, not an error.
     */
    @PostMapping("/documents/sync")
    public ResponseEntity<IngestionJobResponse> startSync() {
        return ResponseEntity.accepted().body(documentService.startSync());
    }

    /** Current state of a sync job, for the portal to poll. */
    @GetMapping("/documents/sync/{jobId}")
    public ResponseEntity<IngestionJobResponse> syncStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(documentService.getSyncStatus(jobId));
    }
}
