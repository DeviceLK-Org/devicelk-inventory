package com.devicelk.inventory.api;

import com.devicelk.inventory.exception.InvalidDocumentException;
import com.devicelk.inventory.service.ProductDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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
}
