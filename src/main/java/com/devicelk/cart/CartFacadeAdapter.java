package com.devicelk.cart;

import com.devicelk.cart.service.CartData;
import com.devicelk.cart.service.CartItemData;
import com.devicelk.cart.service.CartService;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link CartFacade}, a thin adapter over the module-internal
 * {@link CartService}.
 * <p>
 * Package-private: other modules bind to the interface and only this package can
 * see the implementation.
 * <p>
 * Delegates rather than reaching for the repositories, so a cart read by the
 * order module travels the same code path as one read by the cart's own REST
 * endpoints. Two paths to the same data would eventually disagree, and the one
 * that skipped the service is the one that would be missing a rule.
 * <p>
 * Transaction boundaries live on {@code CartService}; adding another layer of
 * {@code @Transactional} here would only obscure where they actually start.
 */
@Service
class CartFacadeAdapter implements CartFacade {

    private final CartService cartService;

    CartFacadeAdapter(CartService cartService) {
        this.cartService = cartService;
    }

    @Override
    public Optional<CartSnapshot> getActiveCart(String userId) {
        // findActiveCart, not getOrCreate: a facade read must not have the side
        // effect of creating a cart for a user who never had one.
        return cartService.findActiveCart(userId).map(CartFacadeAdapter::toSnapshot);
    }

    @Override
    public void markCheckedOut(UUID cartId) {
        cartService.markCheckedOut(cartId);
    }

    /** Narrows the service's view to the subset other modules are given. */
    private static CartSnapshot toSnapshot(CartData cart) {
        return new CartSnapshot(
                cart.cartId(),
                cart.items().stream()
                        .map(CartFacadeAdapter::toItemSnapshot)
                        .toList());
    }

    private static CartItemSnapshot toItemSnapshot(CartItemData item) {
        return new CartItemSnapshot(
                item.productId(),
                item.quantity(),
                item.unitPriceCents(),
                item.currency());
    }
}
