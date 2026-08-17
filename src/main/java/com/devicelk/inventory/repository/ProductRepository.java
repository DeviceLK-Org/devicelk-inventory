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
 * Repository for {@link Product}.
 * <p>
 * {@link JpaSpecificationExecutor} is what enables the dynamic, criteria-based
 * search: {@link #searchSpecification} builds a {@code Specification<Product>} at
 * runtime for {@code findAll(spec, pageable)}.
 */
@Repository
public interface ProductRepository
        extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    boolean existsByNameAndBrand(String name, String brand);

    /**
     * Bulk fetch, so the AI RAG service resolves many products in one query
     * rather than a {@code findById} per id.
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
     * is ANDed in only when supplied, so price and stock constraints resolve as
     * SQL rather than being left to the AI layer.
     * <p>
     * Price bounds are in minor units to match {@code Product.priceCents}.
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
            // Correlated EXISTS rather than a join, for two reasons. Product has no
            // JPA association to Stock — the inverse side of a shared-PK @OneToOne
            // cannot be lazy without bytecode enhancement, so mapping one would
            // fetch stock on every product read. And EXISTS cannot duplicate a
            // product row, so the page's derived count query stays correct.
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
