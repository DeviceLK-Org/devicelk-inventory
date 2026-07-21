package com.devicelk.cart;

import java.util.Optional;
import java.util.UUID;

/**
 * The cart module's published API — the <b>only</b> type other modules may call.
 * <p>
 * Scoped to what checkout needs: read the basket, and mark it converted once an
 * order exists. Adding, updating and removing items are the owning user's
 * operations, driven from the cart's own REST endpoints, and are deliberately
 * absent here — no other module has a reason to edit somebody's basket.
 * <p>
 * Lives in the module's base package so Spring Modulith exposes it while
 * {@code domain}, {@code repository} and {@code service} stay private;
 * {@code ModularityTests} fails the build on any attempt to reach past it.
 */
public interface CartFacade {

    /**
     * Reads a user's open cart.
     * <p>
     * A pure read: unlike the user-facing {@code GET /api/v1/cart}, this does not
     * create a cart when none exists. A caller finding nothing here is looking at
     * a user who never filled a basket, and checkout on an absent cart is an
     * error rather than something to paper over by inventing an empty one.
     *
     * @param userId Keycloak {@code sub} of the cart's owner
     * @return the cart's contents, or {@link Optional#empty()} when the user has
     *         no {@code ACTIVE} cart
     */
    Optional<CartSnapshot> getActiveCart(String userId);

    /**
     * Marks a cart converted into an order, closing it to further edits.
     * <p>
     * Intended to run inside the caller's checkout transaction, so that the order
     * and the cart's closure commit or roll back together — a cart closed
     * alongside an order that never persisted would strand the user's basket.
     *
     * @param cartId the cart to close
     * @throws IllegalArgumentException if no cart exists with that id
     * @throws IllegalStateException    if the cart is not {@code ACTIVE}; a
     *                                  second checkout of one basket is a bug,
     *                                  not an idempotent no-op
     */
    void markCheckedOut(UUID cartId);
}
