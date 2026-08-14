package com.devicelk.inventory.service;

import com.devicelk.inventory.InsufficientStockException;
import com.devicelk.inventory.ReservationLine;

import java.util.List;

/**
 * The reservation lifecycle for stock: hold units, give them back, or consume
 * them.
 * <p>
 * Separate from {@link ProductService} rather than three more methods on it, for
 * the same reason {@code Stock} is a separate entity from {@code Product}: these
 * operations have an entirely different access profile. Catalogue CRUD is
 * occasional, administrative, and touches product rows; this runs on every
 * checkout, is contended, and touches only quantities under an optimistic lock.
 * Keeping them apart means the contention story lives in one small class instead
 * of being diffused through a service that is mostly about something else.
 * <p>
 * <b>Every method is all-or-nothing across the whole list.</b> A partially
 * applied reservation is worse than a rejected one — it holds units for an order
 * that will not exist, and nothing is left holding the information needed to
 * release them.
 */
public interface StockReservationService {

    /**
     * Holds units back for an order that is being placed.
     * <p>
     * Moves {@code availableQty → reservedQty} for every line, in one
     * transaction. If any single line cannot be satisfied, nothing is applied.
     * <p>
     * Intended to be called <i>inside</i> the caller's transaction, so the
     * reservation commits with whatever it is reserving for. See the
     * implementation note on why this must not open a transaction of its own.
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
     * The compensating action for {@link #reserve(List)}. Nothing in the current
     * checkout path calls it, because a failure there rolls the reservation back
     * with the transaction and there is nothing to compensate. It exists for the
     * cases that cannot rely on that rollback: an order cancelled after it
     * committed, a payment refused later, and — once order becomes its own
     * service — a saga unwinding a checkout whose reservation already landed in
     * another database.
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
     * The completing action for {@link #reserve(List)}, and the point at which
     * the units are accounted as having left the warehouse rather than merely
     * being spoken for. Unused today — orders are created {@code PENDING} and
     * nothing yet moves them to {@code PAID} — and belongs to whatever handles
     * payment.
     *
     * @param lines the units to consume
     * @throws IllegalStateException if any line exceeds what is currently
     *                               reserved for that product
     */
    void confirm(List<ReservationLine> lines);
}
