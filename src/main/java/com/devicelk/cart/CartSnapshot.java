package com.devicelk.cart;

import java.util.List;
import java.util.UUID;

/**
 * A cart's contents at one moment, published for other modules to read.
 * <p>
 * The order module's view of a basket at checkout: enough to build order lines
 * and re-check stock, and nothing else. Deliberately not the {@code Cart}
 * entity — handing over a JPA aggregate would expose mutators to a module that
 * has no business mutating a cart, and drag lazy-loading behaviour across a
 * boundary that is meant to survive being turned into a network call.
 * <p>
 * Immutable and detached: holding one and acting on it later is acting on stale
 * data. Checkout must re-read inside its own transaction.
 *
 * @param cartId the cart these contents belong to — the handle to pass back to
 *               {@link CartFacade#markCheckedOut(UUID)}
 * @param items  the basket lines; empty for an open but unfilled cart
 */
public record CartSnapshot(
        UUID cartId,
        List<CartItemSnapshot> items
) {

    /** Defensive copy, so the published view cannot be mutated by a caller. */
    public CartSnapshot {
        items = List.copyOf(items);
    }
}
