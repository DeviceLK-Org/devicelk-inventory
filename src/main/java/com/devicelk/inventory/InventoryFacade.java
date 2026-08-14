package com.devicelk.inventory;

import java.util.List;
import java.util.Optional;

/**
 * The inventory module's published API — the <b>only</b> type other modules may
 * call to read product data.
 * <p>
 * Lives in the module's base package on purpose: Spring Modulith exposes base-package
 * types and treats everything under {@code api}, {@code domain}, {@code repository},
 * {@code service} and {@code exception} as internal. {@code ModularityTests} fails the
 * build if a module reaches past this interface.
 * <p>
 * <b>Two halves, with different rules.</b> The read methods —
 * {@link #getProduct}, {@link #getProducts}, {@link #checkStock} — answer
 * questions about the catalogue and change nothing. The reservation methods —
 * {@link #reserveStock}, {@link #releaseStock}, {@link #confirmReservation} —
 * <b>mutate stock</b>, and are the checkout-time path.
 * <p>
 * That split replaces an earlier rule that nothing here could mutate stock. The
 * reasoning behind that rule still holds and is worth restating, because it is
 * what constrains the reservation methods: a cart holding an item is not a claim
 * on inventory, and browsing must never move units. Reserving is not browsing.
 * It happens once, inside the order module's checkout transaction, at the moment
 * a customer commits to buy — and it has to live here, because stock is
 * inventory's data and no other module may touch those rows directly.
 * <p>
 * <b>Reads are snapshots; a reservation is the only guarantee.</b>
 * {@link #checkStock} answers for the instant it was called and holds nothing
 * back on the strength of it, so a caller that checks and then acts has a race
 * between the two. Only {@link #reserveStock} both checks and claims, atomically.
 * A checkout that calls {@code checkStock} first is not safer for it — only
 * slower, and reasoning from a read its own reservation immediately supersedes.
 * <p>
 * Read errors are modelled as return values rather than exceptions: the internal
 * inventory exceptions live in a private package, so throwing them across the
 * boundary would force callers to import internals to catch them, and an
 * {@link Optional} lets each module map "no such product" onto its own error
 * contract. The reservation methods cannot do that — a failed reservation must
 * abort the caller's transaction, and a return value invites a caller to ignore
 * it — so they throw {@link InsufficientStockException}, published from this
 * package precisely so callers can catch it without reaching past the boundary.
 */
public interface InventoryFacade {

    /**
     * Reads a product's current sellable state.
     *
     * @param productId the product identifier
     * @return the snapshot, or {@link Optional#empty()} if no such product exists
     */
    Optional<ProductSnapshot> getProduct(Long productId);

    /**
     * Reads several products at once.
     * <p>
     * Exists so a caller rendering a list — the cart resolving a name for every
     * line — does it in a fixed number of queries instead of one per element.
     * The cost is flat in the size of {@code productIds}, not linear.
     * <p>
     * <b>Partial by design.</b> Ids with no matching product are simply absent
     * from the result, so the returned list may be shorter than the input and is
     * in no particular order. A caller that needs to line results up with its
     * own data should index by {@link ProductSnapshot#productId()} rather than
     * by position. Absence is not an error here: a product deleted out from
     * under a cart should leave that cart renderable, not break the whole page.
     *
     * @param productIds the products to read; an empty list yields an empty result
     * @return snapshots for those ids that exist
     */
    List<ProductSnapshot> getProducts(List<Long> productIds);

    /**
     * Tests whether the requested quantity could be sold right now.
     * <p>
     * A total predicate: an unknown product answers {@code false} rather than
     * throwing, and a non-positive quantity is treated as trivially satisfiable —
     * rejecting a nonsense quantity is the calling module's input-validation job,
     * not this method's.
     * <p>
     * Callers that also need the price or name should prefer
     * {@link #getProduct(Long)} and compare {@link ProductSnapshot#availableQty()}
     * themselves: this method re-reads the same rows, so using both for one
     * decision costs a second query and can straddle a concurrent stock movement.
     *
     * @param productId the product identifier
     * @param quantity  the number of units wanted
     * @return {@code true} when the product exists and has at least
     *         {@code quantity} units available
     */
    boolean checkStock(Long productId, int quantity);

    /**
     * Holds units back for an order being placed, moving them from available to
     * reserved.
     * <p>
     * <b>All-or-nothing across the whole list.</b> If any single line cannot be
     * satisfied, no units move for any line and
     * {@link InsufficientStockException} names the line that failed. A partial
     * reservation would be the worst of the available outcomes: units held for an
     * order that will never exist, with nothing left holding the information
     * needed to release them.
     * <p>
     * <b>Joins the caller's transaction; it does not open its own.</b> The
     * reservation commits if and only if whatever the caller is reserving for
     * commits. This is what lets checkout state that stock either moved <i>and</i>
     * an order exists, or neither happened — and it is why a caller must not
     * wrap this in a new transaction to "make sure the stock is held". Doing so
     * defeats the guarantee rather than strengthening it.
     * <p>
     * The units are held, not sold. {@link #confirmReservation} completes the
     * sale; {@link #releaseStock} gives them back.
     *
     * @param lines the units to hold; duplicate product ids are summed, and an
     *              empty list is a no-op
     * @throws InsufficientStockException if any line exceeds what is available —
     *                                    including a product with no stock row,
     *                                    or no such product at all, both of which
     *                                    report zero available
     */
    void reserveStock(List<ReservationLine> lines);

    /**
     * Returns previously held units to the shelf: reserved back to available.
     * <p>
     * The compensating action for {@link #reserveStock}, for the failures that a
     * transaction rollback cannot cover. Nothing in the checkout path calls it
     * today, and that is not an omission: a checkout that fails does so inside
     * the transaction that made the reservation, so the rollback releases the
     * units and there is nothing to compensate.
     * <p>
     * What it exists for is everything after that commit — an order cancelled,
     * a payment refused, and, once the order module becomes its own service, a
     * saga unwinding a checkout whose reservation already committed in a database
     * this process can no longer roll back.
     *
     * @param lines the units to return
     * @throws IllegalStateException if any line exceeds what is currently
     *                               reserved for that product; releasing units
     *                               that were never held would manufacture stock
     */
    void releaseStock(List<ReservationLine> lines);

    /**
     * Consumes held units once a sale completes: the reserved count falls and
     * availability is left alone.
     * <p>
     * The completing counterpart to {@link #reserveStock}, and the point at which
     * units are accounted as having left the warehouse rather than merely being
     * spoken for. Availability must not fall again here — {@link #reserveStock}
     * already took it — and this method does not touch it.
     * <p>
     * Unused today: orders are created {@code PENDING} and nothing yet moves them
     * to {@code PAID}. It belongs to whatever comes to handle payment, and is
     * defined now so the reservation lifecycle on this facade is complete rather
     * than half-specified.
     *
     * @param lines the units to consume
     * @throws IllegalStateException if any line exceeds what is currently
     *                               reserved for that product
     */
    void confirmReservation(List<ReservationLine> lines);
}
