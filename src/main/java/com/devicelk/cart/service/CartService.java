package com.devicelk.cart.service;

import com.devicelk.cart.exception.CartItemNotFoundException;
import com.devicelk.cart.exception.InsufficientStockException;
import com.devicelk.cart.exception.UnknownProductException;

import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for a user's shopping cart.
 * <p>
 * <b>Every method that acts on a cart takes {@code userId} and scopes all data
 * access to that user's cart.</b> No method accepts a cart id from a caller, and
 * the ones that take an {@code itemId} resolve it <i>within</i> the requesting
 * user's cart rather than looking it up globally — so an id belonging to someone
 * else's cart is indistinguishable from one that does not exist. Ownership is
 * not an extra check layered on top of these operations; it is the only way they
 * can address data at all.
 * <p>
 * The {@code userId} passed in must come from the authenticated security
 * context. This layer cannot tell a real subject from a forged one, so it
 * trusts what it is given — which is precisely why the layer above must never
 * take it from a request body, a path variable, or a query parameter.
 * <p>
 * <b>Nothing here reserves stock.</b> Availability is checked at the moment of
 * the call and not held; a cart is an intention, not a claim. Reservation
 * happens once, inside the order module's checkout transaction.
 */
public interface CartService {

    /**
     * Returns the user's open cart, creating an empty one if they have none.
     * <p>
     * Safe to call concurrently: two simultaneous first-time requests cannot
     * produce two carts, and neither of them fails.
     *
     * @param userId Keycloak {@code sub} of the owner
     * @return the user's {@code ACTIVE} cart
     */
    CartData getOrCreateActiveCart(String userId);

    /**
     * Reads the user's open cart without creating one.
     * <p>
     * The read {@code CartFacade} exposes to other modules — checkout on a cart
     * that was never created is an error, not a reason to conjure an empty one.
     *
     * @param userId Keycloak {@code sub} of the owner
     * @return the cart, or {@link Optional#empty()} if the user has none
     */
    Optional<CartData> findActiveCart(String userId);

    /**
     * Adds units of a product to the user's cart, or increases the existing line
     * if the product is already in it.
     * <p>
     * The stock check applies to the resulting total, not the increment: adding
     * 3 to an existing line of 8 must be satisfiable as 11.
     * <p>
     * Price and currency are snapshotted from inventory when a line is first
     * created, and left alone when an existing line grows — the customer keeps
     * the price they were quoted when they added the product.
     *
     * @param userId    Keycloak {@code sub} of the owner
     * @param productId the product to add
     * @param quantity  units to add; must be positive
     * @return the cart after the change
     * @throws UnknownProductException    if inventory has no such product
     * @throws InsufficientStockException if the resulting total exceeds
     *                                    available stock
     * @throws IllegalArgumentException   if {@code quantity} is not positive
     */
    CartData addItem(String userId, Long productId, int quantity);

    /**
     * Sets a line to an absolute quantity, re-checking stock for the new figure.
     * <p>
     * A quantity of zero or less removes the line rather than being rejected:
     * the endpoint's job is to make the cart match what the user asked for, and
     * "none of this product" is a coherent request that a 400 would answer with
     * a lecture instead of an outcome.
     * <p>
     * The price snapshot is <b>not</b> refreshed. Changing how many of a thing
     * you want is not a reason to re-price what you already put in the basket.
     *
     * @param userId      Keycloak {@code sub} of the owner
     * @param itemId      the line to change, resolved within this user's cart
     * @param newQuantity the absolute quantity wanted; {@code <= 0} removes the line
     * @return the cart after the change
     * @throws CartItemNotFoundException  if the line is not in this user's cart
     * @throws UnknownProductException    if the line's product has since vanished
     * @throws InsufficientStockException if the new quantity exceeds available stock
     */
    CartData updateItemQuantity(String userId, UUID itemId, int newQuantity);

    /**
     * Removes a line from the user's cart.
     *
     * @param userId Keycloak {@code sub} of the owner
     * @param itemId the line to remove, resolved within this user's cart
     * @return the cart after the change
     * @throws CartItemNotFoundException if the line is not in this user's cart
     */
    CartData removeItem(String userId, UUID itemId);

    /**
     * Empties the user's cart, leaving the cart itself open.
     * <p>
     * Idempotent, and creates a cart for a user who has none so that clearing an
     * empty basket succeeds rather than 404-ing on a request whose desired end
     * state already holds.
     *
     * @param userId Keycloak {@code sub} of the owner
     * @return the now-empty cart
     */
    CartData clearCart(String userId);

    /**
     * Marks a cart converted into an order.
     * <p>
     * Keyed by cart id rather than user id: the order module calls this with the
     * id it read from {@code CartFacade.getActiveCart}, inside its own checkout
     * transaction.
     *
     * @param cartId the cart to close
     * @throws IllegalArgumentException if no cart exists with that id
     * @throws IllegalStateException    if the cart is not {@code ACTIVE}
     */
    void markCheckedOut(UUID cartId);
}
