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
}
