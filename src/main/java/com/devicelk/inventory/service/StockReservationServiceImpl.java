package com.devicelk.inventory.service;

import com.devicelk.inventory.InsufficientStockException;
import com.devicelk.inventory.ReservationLine;
import com.devicelk.inventory.domain.Stock;
import com.devicelk.inventory.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

/**
 * Default {@link StockReservationService}.
 * <p>
 * Package-private: callers bind to the interface, and only this package sees the
 * implementation.
 * <p>
 * <b>Propagation is deliberately the default {@code REQUIRED}, never
 * {@code REQUIRES_NEW}.</b> Checkout calls this from inside its own transaction
 * and needs the reservation to commit or roll back <i>with</i> the order it is
 * reserving for. A new transaction here would commit the stock movement
 * independently, so an order that failed to persist a moment later would leave
 * units held for an order that does not exist — invisible, unreleasable, and
 * gone from the shelf until someone reconciles by hand. Joining the caller's
 * transaction is what makes the atomicity guarantee real rather than aspirational.
 * <p>
 * <b>Concurrency.</b> Two checkouts racing for the last units are settled by the
 * {@code @Version} column on {@link Stock}: both may read the same availability
 * and believe they can proceed, but only one {@code UPDATE} will match the
 * version it read, and the loser fails at flush with an optimistic-lock failure
 * that rolls its whole checkout back. That surfaces as a 500 rather than the 409
 * a rejected reservation gets, which is worth revisiting if contention ever
 * becomes routine — the honest fix is a retry, not a lock upgrade.
 */
@Service
class StockReservationServiceImpl implements StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationServiceImpl.class);

    private final StockRepository stockRepository;

    StockReservationServiceImpl(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    @Transactional
    public void reserve(List<ReservationLine> lines) {
        SortedMap<Long, Integer> wanted = totalsByProduct(lines);
        if (wanted.isEmpty()) {
            return;
        }
        Map<Long, Stock> stockByProductId = loadStock(wanted.keySet());

        // Two passes, on purpose. Checking every line before moving any unit means
        // an unsatisfiable basket leaves the persistence context untouched, rather
        // than relying on rollback to undo mutations that were already applied.
        // Rollback would in fact cover it — but "nothing was ever changed" is a
        // property that holds no matter how a future caller handles the exception,
        // and that is a materially stronger thing to be able to state.
        for (Map.Entry<Long, Integer> line : wanted.entrySet()) {
            Long productId = line.getKey();
            int quantity = line.getValue();
            Stock stock = stockByProductId.get(productId);
            if (stock == null) {
                // No stock row — or no such product. Nothing is known to be
                // sellable, which is the same reading getProductSnapshot takes:
                // a missing record blocks a sale rather than waving one through.
                log.warn("Reservation rejected: no stock row for product {} (requested {})",
                        productId, quantity);
                throw new InsufficientStockException(productId, quantity, 0);
            }
            if (stock.getAvailableQty() < quantity) {
                throw new InsufficientStockException(
                        productId, quantity, stock.getAvailableQty());
            }
        }

        wanted.forEach((productId, quantity) ->
                stockByProductId.get(productId).reserve(quantity));

        log.debug("Reserved {} line(s): {}", wanted.size(), wanted);
    }

    @Override
    @Transactional
    public void release(List<ReservationLine> lines) {
        applyToHeldUnits(lines, Stock::release, "release");
    }

    @Override
    @Transactional
    public void confirm(List<ReservationLine> lines) {
        applyToHeldUnits(lines, Stock::confirmReserved, "confirm");
    }

    /**
     * Shared body of {@link #release(List)} and {@link #confirm(List)}: both walk
     * already-reserved units and differ only in which way they move them.
     * <p>
     * Neither can fail on availability the way {@link #reserve(List)} can — the
     * units are already held — so the only error is a caller asking for more than
     * it holds, which {@link Stock} rejects. That check lives on the entity, so
     * this method does not repeat it; the entity is the thing that knows the
     * current reserved count, and duplicating the guard here would be a second
     * copy to drift out of step.
     */
    private void applyToHeldUnits(List<ReservationLine> lines,
                                  ObjIntConsumer<Stock> movement,
                                  String action) {
        SortedMap<Long, Integer> wanted = totalsByProduct(lines);
        if (wanted.isEmpty()) {
            return;
        }
        Map<Long, Stock> stockByProductId = loadStock(wanted.keySet());

        wanted.forEach((productId, quantity) -> {
            Stock stock = stockByProductId.get(productId);
            if (stock == null) {
                // Distinct from the reserve case: units cannot have been held
                // against a row that does not exist, so this is a caller bug
                // rather than a shortage, and a shortage exception would send
                // the caller looking for a restock that will not help.
                throw new IllegalStateException(
                        "Cannot " + action + " " + quantity + " units of product "
                                + productId + ": no stock row exists.");
            }
            movement.accept(stock, quantity);
        });
    }

    /**
     * Collapses the caller's lines into one total per product.
     * <p>
     * Two things fall out of this, both of which matter. Duplicate lines for the
     * same product are summed, so a basket asking for 3 and then 4 is checked as
     * 7 against availability rather than twice as 3 and 4 — the latter would let
     * a basket pass that cannot actually be fulfilled, and would report the wrong
     * figure when it failed.
     * <p>
     * And the result is sorted by product id, which fixes the order in which rows
     * are touched and therefore the order locks are taken at flush. Two checkouts
     * for overlapping products that walked their lines in caller-supplied order
     * could each hold what the other needs next; sorting means they contend for
     * the same row first and one simply waits.
     */
    private static SortedMap<Long, Integer> totalsByProduct(List<ReservationLine> lines) {
        return lines.stream().collect(Collectors.toMap(
                ReservationLine::productId,
                ReservationLine::quantity,
                Integer::sum,
                TreeMap::new));
    }

    /**
     * Loads every stock row the operation touches in one query.
     * <p>
     * Fixed cost in the number of distinct products, rather than a lookup per
     * line. The returned entities are managed, so the mutations applied to them
     * are written by dirty checking at flush — there is no {@code save} call
     * here, and adding one would suggest it is what persists the change when it
     * would in fact be a no-op.
     */
    private Map<Long, Stock> loadStock(Collection<Long> productIds) {
        return stockRepository.findByProductIdIn(List.copyOf(productIds)).stream()
                .collect(Collectors.toMap(Stock::getProductId, Function.identity()));
    }
}
