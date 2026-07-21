package com.devicelk.inventory.repository;

import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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

    /**
     * Dynamic filter backing the {@code SearchProducts} gRPC RPC. Each criterion
     * is ANDed in only when supplied, so every price/stock constraint is resolved
     * as SQL — never left to the AI layer. Run it through
     * {@code findAll(spec, pageable)} from {@link JpaSpecificationExecutor}.
     *
     * @param category    exact category to match, or {@code null} for any
     * @param minPrice    inclusive lower price bound, or {@code null} for none
     * @param maxPrice    inclusive upper price bound, or {@code null} for none
     * @param inStockOnly when {@code true}, restrict to {@code stockQuantity > 0}
     */
    static Specification<Product> searchSpecification(Category category,
                                                      BigDecimal minPrice,
                                                      BigDecimal maxPrice,
                                                      boolean inStockOnly) {
        Specification<Product> spec = (root, query, cb) -> cb.conjunction();

        if (category != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (minPrice != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice));
        }
        if (maxPrice != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice));
        }
        if (inStockOnly) {
            spec = spec.and((root, query, cb) -> cb.greaterThan(root.get("stockQuantity"), 0));
        }
        return spec;
    }
}
