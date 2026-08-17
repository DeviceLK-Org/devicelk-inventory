package com.devicelk.inventory.service;

import com.devicelk.inventory.ProductSnapshot;
import com.devicelk.inventory.api.ProductResponseDTO;
import com.devicelk.inventory.api.ProductWriteRequest;
import com.devicelk.inventory.exception.ProductNotFoundException;
import com.devicelk.inventory.exception.StockNotFoundException;
import com.devicelk.inventory.domain.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Business logic for products. Keeps the controller decoupled from the
 * implementation and easy to mock in tests.
 */
public interface ProductService {

    /**
     * Persists a new product together with its stock row, in one transaction.
     *
     * @param request the validated product to store
     * @return the saved product, including its generated id
     * @throws IllegalArgumentException if the name/brand pair already exists
     */
    ProductResponseDTO createProduct(ProductWriteRequest request);

    /**
     * @return every product in the inventory, or an empty list
     */
    List<ProductResponseDTO> getAllProducts();

    /**
     * @param id the product id
     * @return the matching product
     * @throws ProductNotFoundException if no product exists for the given id
     */
    ProductResponseDTO getProductById(Long id);

    /**
     * Updates an existing product and its stock row.
     *
     * @param id      the id of the product to update
     * @param request the new field values
     * @return the updated product
     * @throws ProductNotFoundException if no product exists for the given id
     * @throws StockNotFoundException   if the product has no stock row
     * @throws IllegalArgumentException if the new name/brand collides with
     *                                  a different product
     */
    ProductResponseDTO updateProduct(Long id, ProductWriteRequest request);

    /**
     * @param id the id of the product to delete
     * @throws ProductNotFoundException if no product exists for the given id
     */
    void deleteProduct(Long id);

    /**
     * Adjusts a product's stock level by a relative amount.
     *
     * @param id             the id of the product to adjust
     * @param quantityChange signed change to apply (positive adds, negative removes)
     * @return the updated product
     * @throws ProductNotFoundException if no product exists for the given id
     * @throws StockNotFoundException   if the product has no stock row
     * @throws IllegalArgumentException if the adjustment would drive stock below zero
     */
    ProductResponseDTO adjustStock(Long id, Integer quantityChange);

    /**
     * @param ids the product identifiers to fetch
     * @return the matching products, or an empty list
     */
    List<ProductResponseDTO> getProductsByIds(List<Long> ids);

    /**
     * Reads a product in the cents-native form other services consume over gRPC.
     * <p>
     * Differs from {@link #getProductById(Long)} in two ways that matter to a
     * caller storing the result: it carries {@code priceCents} verbatim rather
     * than the display {@code BigDecimal}, and a missing product is an empty
     * {@link Optional} rather than a {@link ProductNotFoundException} — that
     * exception is internal to this module, so callers outside it cannot catch it.
     *
     * @param id the product id
     * @return the snapshot, or {@link Optional#empty()} if no such product exists
     */
    Optional<ProductSnapshot> getProductSnapshot(Long id);

    /**
     * Batch form of {@link #getProductSnapshot(Long)}, resolving products and
     * stock rows in one query each however many ids are supplied.
     * <p>
     * Ids that match nothing are omitted, so the result may be shorter than the
     * input and has no positional relationship to it.
     *
     * @param ids the product ids to read
     * @return snapshots for the ids that exist, in no guaranteed order
     */
    List<ProductSnapshot> getProductSnapshots(List<Long> ids);

    /**
     * Paginated, filtered search. Every filter is optional — a null or blank
     * argument omits that predicate — and the query is built dynamically with
     * Spring Data JPA {@code Specification}s.
     *
     * @param name     partial, case-insensitive match ({@code like %name%})
     * @param brand    case-insensitive exact match
     * @param category exact match against the {@link Category} enum name
     * @param minPrice inclusive lower price bound
     * @param maxPrice inclusive upper price bound
     * @param pageable paging and sorting directives
     * @return a page of matching products
     */
    Page<ProductResponseDTO> searchProducts(String name,
                                            String brand,
                                            String category,
                                            BigDecimal minPrice,
                                            BigDecimal maxPrice,
                                            Pageable pageable);
}
