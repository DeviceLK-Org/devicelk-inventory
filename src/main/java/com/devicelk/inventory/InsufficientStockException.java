package com.devicelk.inventory;

/**
 * Raised when inventory cannot hold back the units a reservation asked for.
 * <p>
 * <b>Published from the inventory module's base package, and distinct from the
 * cart module's exception of the same name.</b> The duplication is deliberate,
 * not an oversight. The two describe different events: the cart's is a soft
 * warning that a basket already exceeds what is on the shelf, thrown while
 * nothing is at stake and nothing has moved. This one is thrown by
 * {@link InventoryFacade#reserveStock(java.util.List)}, at the moment units are
 * being claimed, and it aborts a checkout that would otherwise have sold stock
 * twice.
 * <p>
 * They also cannot be merged even if the meanings converged: the cart's lives in
 * {@code cart.exception}, a module-internal package, so the order module could
 * not catch it without reaching past the cart's facade — exactly the boundary
 * {@code ModularityTests} exists to enforce. An exception that crosses a module
 * boundary must be published by the module that throws it.
 * <p>
 * Carries the failing product and both quantities rather than only a message, so
 * a caller can build its own response — a REST layer rendering a field error, a
 * future saga deciding which lines to compensate — without parsing prose.
 * <p>
 * Maps to HTTP 409: the request is well-formed and the client did nothing wrong;
 * it conflicts with the current state of the world and may well succeed later.
 */
public class InsufficientStockException extends RuntimeException {

    private final Long productId;
    private final int requested;
    private final int available;

    /**
     * @param productId the product that could not be satisfied
     * @param requested units the reservation asked for
     * @param available units actually sellable at that moment; {@code 0} also
     *                  covers a product with no stock row at all, since nothing
     *                  is known to be sellable in that case
     */
    public InsufficientStockException(Long productId, int requested, int available) {
        super("Insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available + ".");
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    /** The product whose stock could not cover the request. */
    public Long getProductId() {
        return productId;
    }

    /** Units the reservation asked for. */
    public int getRequested() {
        return requested;
    }

    /** Units that were actually sellable when the reservation was attempted. */
    public int getAvailable() {
        return available;
    }
}
