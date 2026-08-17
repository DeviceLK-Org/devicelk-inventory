package com.devicelk.inventory.api;

import com.devicelk.inventory.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

import java.math.BigDecimal;
import java.util.List;

/**
 * REST API for the inventory, rooted at {@code /inventory}.
 * <p>
 * A thin adapter: validate input, delegate to {@link ProductService}, shape the
 * HTTP response. No business logic here.
 */
@RestController
@RequestMapping("/inventory")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Adds a new product to the inventory, along with its stock row.
     *
     * @param request request body, validated by Bean Validation annotations
     * @return HTTP 201 Created with the persisted product
     */
    @PostMapping
    public ResponseEntity<ProductResponseDTO> addProduct(@Valid @RequestBody ProductWriteRequest request) {
        ProductResponseDTO created = productService.createProduct(request);
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
     * Paginated, filtered listing. Every filter is optional and ANDed — omitting
     * a parameter drops that restriction. Clients pass {@code page}, {@code size}
     * and {@code sort} (e.g. {@code ?sort=price,desc}); {@link PageableDefault}
     * falls back to page 0, size 10, sorted by {@code id} ascending.
     *
     * @param name     partial, case-insensitive name match ({@code like %name%})
     * @param brand    case-insensitive exact brand match
     * @param category exact {@code Category} enum match
     * @param minPrice inclusive lower price bound
     * @param maxPrice inclusive upper price bound
     * @param pageable paging and sorting directives (page/size/sort)
     * @return HTTP 200 OK with the matching page of products
     */
    @GetMapping("/search")
    public ResponseEntity<Page<ProductResponseDTO>> searchProducts(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @PageableDefault(page = 0, size = 10, sort = "id", direction = Sort.Direction.ASC)
            Pageable pageable) {
        Page<ProductResponseDTO> results =
                productService.searchProducts(name, brand, category, minPrice, maxPrice, pageable);
        return ResponseEntity.ok(results);
    }

    /**
     * Replaces the fields of an existing product, and its stock levels.
     *
     * @param id      the product identifier
     * @param request request body, validated by Bean Validation annotations
     * @return HTTP 200 OK with the updated product
     */
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponseDTO> updateProduct(@PathVariable Long id,
                                                            @Valid @RequestBody ProductWriteRequest request) {
        return ResponseEntity.ok(productService.updateProduct(id, request));
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
