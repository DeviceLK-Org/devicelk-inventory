package com.devicelk.inventory.service;

import com.devicelk.inventory.InsufficientStockException;
import com.devicelk.inventory.ReservationLine;

import java.util.List;

/**
 * The reservation lifecycle for stock: hold units, give them back, or consume
 * them.
 * <p>
 * Separate from {@link ProductService} for the same reason {@code Stock} is a
 * separate entity from {@code Product}: catalogue CRUD is occasional and
 * administrative, while this runs on every checkout, is contended, and touches
 * only quantities under an optimistic lock.
 * <p>
 * Every method is all-or-nothing across the whole list. A partially applied
 * reservation holds units for an order that will not exist, with nothing left
 * holding the information needed to release them.
 */
public interface StockReservationService {

    /**
     * Holds units back for an order that is being placed.
     * <p>
     * Moves {@code availableQty → reservedQty} for every line, in one
     * transaction. If any single line cannot be satisfied, nothing is applied.
     * <p>
     * Called over gRPC with no ambient transaction, so it commits on its own —
     * a remote caller compensates with {@link #release(List)} rather than
     * relying on a rollback.
     *
     * @param lines the units to hold; duplicate product ids are summed, and an
     *              empty list is a no-op
     * @throws InsufficientStockException if any line exceeds what is available —
     *                                    including a product that has no stock
     *                                    row, or none at all
     */
    void reserve(List<ReservationLine> lines);

    /**
     * Returns previously held units to the shelf: {@code reservedQty →
     * availableQty}.
     * <p>
     * The compensating action for {@link #reserve(List)}: a checkout that failed
     * after its reservation committed, an order cancelled later, or a payment
     * refused.
     *
     * @param lines the units to return
     * @throws IllegalStateException if any line exceeds what is currently
     *                               reserved for that product
     */
    void release(List<ReservationLine> lines);

    /**
     * Consumes held units once the sale completes: {@code reservedQty} falls and
     * {@code availableQty} is left alone.
     * <p>
     * The completing action for {@link #reserve(List)}: the units are now
     * accounted as having left the warehouse. Unused today — orders are created
     * {@code PENDING} and nothing moves them to {@code PAID} yet.
     *
     * @param lines the units to consume
     * @throws IllegalStateException if any line exceeds what is currently
     *                               reserved for that product
     */
    void confirm(List<ReservationLine> lines);
}
