package com.devicelk.inventory;

/**
 * A request to move {@code quantity} units of one product between availability
 * states — the unit of work for every method in {@link InventoryFacade}'s
 * reservation lifecycle.
 * <p>
 * One record serves reserve, release and confirm: all three say "this many units
 * of this product", and the direction belongs in the method name rather than a
 * flag here, which would let a "release" line be handed to {@code reserveStock}.
 * <p>
 * Quantity is validated at construction so an invalid line cannot exist. A
 * non-positive quantity reaching {@code reserveStock} would <i>increase</i>
 * available stock while claiming to hold units back.
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
