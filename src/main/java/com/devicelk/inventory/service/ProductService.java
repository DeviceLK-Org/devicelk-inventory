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
 * <b>Service</b> layer contract for product business logic.
 * <p>
 * Programming against an interface keeps the controller decoupled from the
 * concrete implementation and makes the logic easy to mock in unit tests.
 */
public interface ProductService {

    /**
     * Persists a new product together with its stock row, in one transaction.
     *
     * @param request the validated product to store
     * @return the saved product as a response DTO (including its generated id)
     * @throws IllegalArgumentException
     *         if the name/brand pair already exists
     */
    ProductResponseDTO createProduct(ProductWriteRequest request);

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
     * Updates an existing product, and its stock row, with new field values.
     *
     * @param id      the id of the product to update
     * @param request the new field values
     * @return the updated product as a response DTO
     * @throws ProductNotFoundException
     *         if no product exists for the given id
     * @throws StockNotFoundException
     *         if the product exists but has no stock row
     * @throws IllegalArgumentException
     *         if the new name/brand collides with a different product
     */
    ProductResponseDTO updateProduct(Long id, ProductWriteRequest request);

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
     * @throws StockNotFoundException
     *         if the product exists but has no stock row
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
     * Reads a product in the cents-native form other modules consume through
     * the gRPC API that other services read the catalogue through.
     * <p>
     * Distinct from {@link #getProductById(Long)} in two ways that matter to a
     * caller storing the result. It carries {@code priceCents} verbatim instead
     * of the {@code BigDecimal} the REST DTO renders for display, so a module
     * snapshotting a price keeps inventory's exact integer rather than one
     * reconstructed from a formatted string. And a missing product is an empty
     * {@link Optional} rather than a {@link ProductNotFoundException}: that
     * exception is internal to this module, so a sibling module cannot catch it
     * without violating the boundary.
     *
     * @param id the product id
     * @return the snapshot, or {@link Optional#empty()} if no such product exists
     */
    Optional<ProductSnapshot> getProductSnapshot(Long id);

    /**
     * Batch form of {@link #getProductSnapshot(Long)}.
     * <p>
     * Resolves products and their stock rows in a fixed number of queries — one
     * for each table — however many ids are supplied, so a caller rendering a
     * list never degenerates into a per-row lookup.
     * <p>
     * Ids that match nothing are omitted rather than reported, so the result may
     * be shorter than the input and carries no positional relationship to it.
     *
     * @param ids the product ids to read
     * @return snapshots for the ids that exist, in no guaranteed order
     */
    List<ProductSnapshot> getProductSnapshots(List<Long> ids);

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
     * @param category exact match against the {@link Category}
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
