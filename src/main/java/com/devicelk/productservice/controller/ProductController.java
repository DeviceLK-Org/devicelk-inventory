package com.devicelk.productservice.controller;

import com.devicelk.productservice.domain.Product;
import com.devicelk.productservice.dto.ProductResponseDTO;
import com.devicelk.productservice.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
