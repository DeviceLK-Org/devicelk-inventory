package com.devicelk.service;

import com.devicelk.domain.Product;
import com.devicelk.dto.ProductResponseDTO;
import com.devicelk.exception.ProductNotFoundException;

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
}
