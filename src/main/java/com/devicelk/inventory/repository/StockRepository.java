package com.devicelk.inventory.repository;

import com.devicelk.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * <b>Repository</b> layer for {@link Stock}.
 * <p>
 * Keyed by product id, so {@code findById(productId)} is the single-row lookup
 * and {@code findByProductIdIn} the bulk one. Read paths that need a product
 * <em>and</em> its quantities should not combine the two repositories per row —
 * they join, so a page of products costs one query rather than one per product.
 */
@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * Bulk fetch: returns the stock rows for every id in the given list.
     * <p>
     * Products created before their stock row exists are simply absent from the
     * result, so callers must tolerate a missing entry rather than assume the
     * result is the same size as the input.
     *
     * @param productIds the product identifiers to look up
     * @return the matching stock rows (empty list if none match)
     */
    List<Stock> findByProductIdIn(List<Long> productIds);
}
