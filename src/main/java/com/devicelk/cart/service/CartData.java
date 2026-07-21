package com.devicelk.cart.service;

import com.devicelk.cart.domain.CartStatus;

import java.util.List;
import java.util.UUID;

/**
 * A cart's full contents as the service layer reports it.
 * <p>
 * Every {@code CartService} method returns one of these rather than the
 * {@code Cart} entity: the entity is a managed, mutable, lazily-initialised
 * object whose behaviour changes the moment it leaves its transaction, and
 * handing one to a controller invites exactly the bugs that produces.
 *
 * @param cartId the cart's id
 * @param userId Keycloak {@code sub} of the owner — carried so callers can
 *               assert whose cart they were handed rather than assume it
 * @param status the cart's lifecycle state
 * @param items  the basket lines; empty for an open but unfilled cart
 */
public record CartData(
        UUID cartId,
        String userId,
        CartStatus status,
        List<CartItemData> items
) {

    /** Defensive copy, so a caller cannot mutate the returned view. */
    public CartData {
        items = List.copyOf(items);
    }

    /**
     * Basket total in minor units.
     * <p>
     * Summed in integer cents, never through a floating-point type — the whole
     * reason prices are stored this way.
     */
    public long totalCents() {
        return items.stream().mapToLong(CartItemData::lineTotalCents).sum();
    }
}
