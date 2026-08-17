package com.devicelk.inventory.repository;

import com.devicelk.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link Stock}, keyed by product id — {@code findById(productId)}
 * for one row, {@code findByProductIdIn} for many.
 * <p>
 * Read paths needing products <em>and</em> quantities should batch rather than
 * pairing the two repositories per row, so a page costs one query each.
 */
@Repository
public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * Bulk fetch of stock rows.
     * <p>
     * A product without a stock row is simply absent, so callers must tolerate a
     * result shorter than the input.
     *
     * @param productIds the product identifiers to look up
     * @return the matching stock rows (empty list if none match)
     */
    List<Stock> findByProductIdIn(List<Long> productIds);
}
