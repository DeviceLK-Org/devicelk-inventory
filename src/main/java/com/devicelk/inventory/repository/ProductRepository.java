package com.devicelk.inventory.repository;

import com.devicelk.inventory.domain.Category;
import com.devicelk.inventory.domain.Product;
import com.devicelk.inventory.domain.Stock;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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
     * Finds the product owning a given spec-document key, if any.
     * <p>
     * Used when a document is deleted, to clear the now-dangling reference.
     * Returns empty for the legacy documents that sit at the bucket root and
     * belong to no product.
     *
     * @param documentKey the S3 object key
     */
    Optional<Product> findByDocumentKey(String documentKey);

    /**
     * Bulk fetch by spec-document key, so the knowledge-base listing can label
     * every row with its product in one query rather than one lookup per file.
     *
     * @param documentKeys the S3 object keys to resolve
     * @return the products owning any of those keys (empty list if none do)
     */
    List<Product> findByDocumentKeyIn(Collection<String> documentKeys);

    /**
     * Dynamic filter backing the {@code SearchProducts} gRPC RPC. Each criterion
     * is ANDed in only when supplied, so every price/stock constraint is resolved
     * as SQL — never left to the AI layer. Run it through
     * {@code findAll(spec, pageable)} from {@link JpaSpecificationExecutor}.
     * <p>
     * Price bounds are in minor units to match {@code Product.priceCents}; the
     * caller converts once, via {@code Money.toCents}.
     *
     * @param category       exact category to match, or {@code null} for any
     * @param minPriceCents  inclusive lower price bound in cents, or {@code null} for none
     * @param maxPriceCents  inclusive upper price bound in cents, or {@code null} for none
     * @param inStockOnly    when {@code true}, restrict to products with available stock
     */
    static Specification<Product> searchSpecification(Category category,
                                                      Long minPriceCents,
                                                      Long maxPriceCents,
                                                      boolean inStockOnly) {
        Specification<Product> spec = (root, query, cb) -> cb.conjunction();

        if (category != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        if (minPriceCents != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("priceCents"), minPriceCents));
        }
        if (maxPriceCents != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("priceCents"), maxPriceCents));
        }
        if (inStockOnly) {
            // Correlated EXISTS against inventory.stock rather than a join.
            //
            // Product deliberately has no JPA association to Stock: the inverse
            // side of a shared-PK @OneToOne cannot be lazy without bytecode
            // enhancement, so mapping one would make Hibernate fetch stock on
            // every product read — reintroducing the N+1 this filter avoids.
            //
            // EXISTS resolves in the same single statement a join would, but it
            // cannot duplicate a product row, so the page's total count stays
            // correct when Spring Data derives the count query from this spec.
            spec = spec.and((root, query, cb) -> {
                Subquery<Long> availableStock = query.subquery(Long.class);
                Root<Stock> stock = availableStock.from(Stock.class);
                availableStock.select(stock.get("productId"))
                        .where(cb.equal(stock.get("productId"), root.get("id")),
                               cb.greaterThan(stock.get("availableQty"), 0));
                return cb.exists(availableStock);
            });
        }
        return spec;
    }
}
