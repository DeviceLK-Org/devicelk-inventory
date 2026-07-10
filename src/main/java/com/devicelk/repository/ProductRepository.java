package com.devicelk.repository;

import com.devicelk.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <b>Repository</b> layer for {@link Product}.
 * <p>
 * Extending {@link JpaRepository} provides ready-made CRUD operations
 * ({@code save}, {@code findById}, {@code findAll}, {@code deleteById}, ...)
 * without any boilerplate implementation. Spring Data generates the proxy
 * at runtime.
 * <p>
 * Extending {@link JpaSpecificationExecutor} additionally enables dynamic,
 * criteria-based queries — required by the advanced search/filtering feature
 * which builds a {@code Specification<Product>} at runtime and runs it through
 * {@code findAll(spec, pageable)}.
 */
@Repository
public interface ProductRepository
        extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    // Checks if a product with the exact same name and brand already exists
    boolean existsByNameAndBrand(String name, String brand);

    /**
     * Bulk fetch: returns every product whose id appears in the given list.
     * Backs the AI RAG service so it can resolve many products in one query
     * instead of issuing a {@code findById} per id.
     *
     * @param ids the product identifiers to look up
     * @return the matching products (empty list if none match)
     */
    List<Product> findByIdIn(List<Long> ids);
}
