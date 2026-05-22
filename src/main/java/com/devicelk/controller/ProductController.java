package com.devicelk.controller;

import com.devicelk.domain.Product;
import com.devicelk.dto.ProductResponseDTO;
import com.devicelk.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <b>Controller</b> layer: exposes the inventory REST API.
 * <p>
 * All endpoints are rooted at {@code /inventory}. The controller is a thin
 * adapter — it validates input, delegates to {@link ProductService}, and
 * shapes the HTTP response. No business logic lives here.
 */
@RestController
@RequestMapping("/inventory")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Adds a new product to the inventory.
     *
     * @param product request body, validated by Bean Validation annotations
     * @return HTTP 201 Created with the persisted product
     */
    @PostMapping
    public ResponseEntity<ProductResponseDTO> addProduct(@Valid @RequestBody Product product) {
        ProductResponseDTO created = productService.createProduct(product);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    /**
     * Returns every product in the inventory.
     *
     * @return HTTP 200 OK with the (possibly empty) list of products
     */
    @GetMapping
    public ResponseEntity<List<ProductResponseDTO>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    /**
     * Returns a single product by id.
     *
     * @param id the product identifier
     * @return HTTP 200 OK with the product, or HTTP 404 if it does not exist
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDTO> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }

    /**
     * Bulk fetches products for a list of ids — backs the AI RAG service.
     *
     * @param ids the product identifiers, e.g. {@code /inventory/bulk?ids=1,2,3}
     * @return HTTP 200 OK with the (possibly empty) list of matching products
     */
    @GetMapping("/bulk")
    public ResponseEntity<List<ProductResponseDTO>> getProductsByIds(@RequestParam List<Long> ids) {
        return ResponseEntity.ok(productService.getProductsByIds(ids));
    }

    /**
     * Replaces the fields of an existing product.
     *
     * @param id      the product identifier
     * @param product request body, validated by Bean Validation annotations
     * @return HTTP 200 OK with the updated product
     */
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponseDTO> updateProduct(@PathVariable Long id,
                                                            @Valid @RequestBody Product product) {
        return ResponseEntity.ok(productService.updateProduct(id, product));
    }

    /**
     * Adjusts a product's stock level by a relative amount.
     *
     * @param id             the product identifier
     * @param quantityChange the signed change to apply (positive adds, negative removes)
     * @return HTTP 200 OK with the updated product
     */
    @PatchMapping("/{id}/stock")
    public ResponseEntity<ProductResponseDTO> adjustStock(@PathVariable Long id,
                                                          @RequestParam Integer quantityChange) {
        return ResponseEntity.ok(productService.adjustStock(id, quantityChange));
    }

    /**
     * Removes a product from the inventory.
     *
     * @param id the product identifier
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
