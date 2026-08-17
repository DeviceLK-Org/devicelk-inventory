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
 * Default {@link StockReservationService}. Package-private; callers bind to the
 * interface.
 * <p>
 * Propagation is the default {@code REQUIRED}. The only inbound caller is
 * {@code ProductGrpcServiceImpl}, which has no ambient transaction, so each
 * operation commits on its own — remote callers must compensate with
 * {@link #release(List)} rather than relying on a rollback.
 * <p>
 * Two checkouts racing for the last units are settled by the {@code @Version}
 * column on {@link Stock}: only one {@code UPDATE} matches the version it read,
 * and the loser fails at flush with an optimistic-lock failure. That surfaces as
 * ABORTED to the caller; if contention becomes routine the fix is a retry, not a
 * lock upgrade.
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

        // Two passes: check every line before moving any unit, so an unsatisfiable
        // basket leaves the persistence context untouched rather than relying on
        // rollback to undo mutations already applied.
        for (Map.Entry<Long, Integer> line : wanted.entrySet()) {
            Long productId = line.getKey();
            int quantity = line.getValue();
            Stock stock = stockByProductId.get(productId);
            if (stock == null) {
                // No stock row, or no such product: nothing is known to be sellable,
                // so a missing record blocks the sale rather than waving it through.
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
     * Shared body of {@link #release(List)} and {@link #confirm(List)}; both walk
     * already-reserved units and differ only in which way they move them.
     * <p>
     * Neither can fail on availability the way {@link #reserve(List)} can, since
     * the units are already held. The only error is asking for more than is held,
     * which {@link Stock} rejects — not repeated here, because the entity is what
     * knows the current reserved count.
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
                // A caller bug, not a shortage: units cannot have been held against
                // a row that does not exist, and a shortage exception would send
                // the caller looking for a restock that will not help.
                throw new IllegalStateException(
                        "Cannot " + action + " " + quantity + " units of product "
                                + productId + ": no stock row exists.");
            }
            movement.accept(stock, quantity);
        });
    }

    /**
     * Collapses the caller's lines into one total per product, which does two
     * things:
     * <ul>
     *   <li>Duplicate lines are summed, so 3 then 4 is checked as 7 rather than
     *       twice separately, which would pass a basket that cannot be fulfilled.</li>
     *   <li>Sorting by product id fixes the order rows are touched, and therefore
     *       the order locks are taken at flush, so two overlapping checkouts
     *       contend on the same row first instead of deadlocking.</li>
     * </ul>
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
     * The returned entities are managed, so mutations are written by dirty
     * checking at flush — there is deliberately no {@code save} call, which would
     * be a no-op that looks like the thing persisting the change.
     */
    private Map<Long, Stock> loadStock(Collection<Long> productIds) {
        return stockRepository.findByProductIdIn(List.copyOf(productIds)).stream()
                .collect(Collectors.toMap(Stock::getProductId, Function.identity()));
    }
}
