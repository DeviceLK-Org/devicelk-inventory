package com.devicelk.inventory;

/**
 * A request to move {@code quantity} units of one product between availability
 * states — the unit of work for every method in {@link InventoryFacade}'s
 * reservation lifecycle.
 * <p>
 * One record serves reserve, release and confirm because all three describe the
 * same thing: this many units of this product. What differs is the direction,
 * and that belongs in the method name rather than in a flag on the payload — a
 * {@code direction} field here would make it possible to construct a "release"
 * line and hand it to {@code reserveStock}.
 * <p>
 * <b>Quantity is validated at construction</b>, so an invalid line cannot exist
 * to be passed anywhere. This matters more than it looks: a non-positive
 * quantity reaching {@code reserveStock} would <i>increase</i> available stock
 * while claiming to hold units back, quietly manufacturing inventory. Rejecting
 * it here means no caller can create that state even by accident, rather than
 * every method having to remember to re-check.
 *
 * @param productId the product to move units of; never {@code null}
 * @param quantity  units to move; must be greater than zero
 */
public record ReservationLine(Long productId, int quantity) {

    public ReservationLine {
        if (productId == null) {
            throw new IllegalArgumentException("Reservation line requires a product id.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Reservation quantity must be greater than zero, got " + quantity
                            + " for product " + productId + ".");
        }
    }
}
