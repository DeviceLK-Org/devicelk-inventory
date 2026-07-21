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
 * <b>Read-only.</b> Nothing here mutates stock. Reserving units is a checkout-time
 * concern that belongs to the order module's transaction; a cart holding an item is
 * not a claim on inventory.
 * <p>
 * Errors are modelled as return values rather than exceptions. The inventory
 * exceptions live in an internal package, so throwing them across the boundary
 * would force callers to import internals to catch them; an {@link Optional}
 * lets each module map "no such product" onto its own error contract.
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
}
