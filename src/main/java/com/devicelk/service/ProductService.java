package com.devicelk.service;

import com.devicelk.domain.Product;
import com.devicelk.dto.ProductResponseDTO;
import com.devicelk.exception.ProductNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

/**
 * <b>Service</b> layer contract for product business logic.
 * <p>
 * Programming against an interface keeps the controller decoupled from the
 * concrete implementation and makes the logic easy to mock in unit tests.
 */
public interface ProductService {

    /**
     * Persists a new product.
     *
     * @param product the validated product to store
     * @return the saved product as a response DTO (including its generated id)
     */
    ProductResponseDTO createProduct(Product product);

    /**
     * Retrieves every product in the inventory.
     *
     * @return all products as response DTOs (empty list if none exist)
     */
    List<ProductResponseDTO> getAllProducts();

    /**
     * Retrieves a single product by its identifier.
     *
     * @param id the product id
     * @return the matching product as a response DTO
     * @throws ProductNotFoundException
     *         if no product exists for the given id
     */
    ProductResponseDTO getProductById(Long id);

    /**
     * Updates an existing product with new field values.
     *
     * @param id             the id of the product to update
     * @param productDetails the new field values
     * @return the updated product as a response DTO
     * @throws ProductNotFoundException
     *         if no product exists for the given id
     * @throws IllegalArgumentException
     *         if the new name/brand collides with a different product
     */
    ProductResponseDTO updateProduct(Long id, Product productDetails);

    /**
     * Removes a product from the inventory.
     *
     * @param id the id of the product to delete
     * @throws ProductNotFoundException
     *         if no product exists for the given id
     */
    void deleteProduct(Long id);

    /**
     * Adjusts the stock level of a product by a relative amount.
     *
     * @param id             the id of the product to adjust
     * @param quantityChange the signed change to apply (positive to add,
     *                       negative to remove)
     * @return the updated product as a response DTO
     * @throws ProductNotFoundException
     *         if no product exists for the given id
     * @throws IllegalArgumentException
     *         if the adjustment would drive stock below zero
     */
    ProductResponseDTO adjustStock(Long id, Integer quantityChange);

    /**
     * Bulk retrieves products for the given identifiers.
     *
     * @param ids the product identifiers to fetch
     * @return the matching products as response DTOs (empty list if none match)
     */
    List<ProductResponseDTO> getProductsByIds(List<Long> ids);

    /**
     * Performs a paginated, filtered search over the inventory.
     * <p>
     * Every filter is optional: a {@code null} or blank argument simply omits
     * that predicate, so callers can mix and match criteria freely. The
     * implementation builds the query dynamically with Spring Data JPA
     * {@code Specification}s.
     *
     * @param name     partial, case-insensitive match on the product name
     *                 ({@code like %name%}); ignored when null/blank
     * @param brand    case-insensitive exact match on the brand;
     *                 ignored when null/blank
     * @param category exact match against the {@link com.devicelk.domain.Category}
     *                 enum name; ignored when null/blank
     * @param minPrice inclusive lower price bound; ignored when null
     * @param maxPrice inclusive upper price bound; ignored when null
     * @param pageable paging and sorting directives
     * @return a page of matching products as response DTOs
     */
    Page<ProductResponseDTO> searchProducts(String name,
                                            String brand,
                                            String category,
                                            BigDecimal minPrice,
                                            BigDecimal maxPrice,
                                            Pageable pageable);
}
