package com.devicelk.inventory;

/**
 * Raised when inventory cannot hold back the units a reservation asked for.
 * <p>
 * Published from the module's base package so callers outside it can catch it —
 * an exception that crosses a module boundary must be published by the module
 * that throws it.
 * <p>
 * Thrown as units are actually claimed, aborting an operation that would
 * otherwise oversell. Carries the failing product and both quantities so callers
 * can build their own response without parsing the message. Maps to HTTP 409.
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
