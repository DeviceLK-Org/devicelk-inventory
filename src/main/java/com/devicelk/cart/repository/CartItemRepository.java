package com.devicelk.cart.repository;

import com.devicelk.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence gateway for individual {@link CartItem} rows.
 * <p>
 * Module-internal, on the same terms as {@link CartRepository}.
 * <p>
 * Items are normally reached by navigating from the {@code Cart} aggregate, and
 * inserts/deletes happen through its cascade. This repository exists for the
 * endpoints that address a line directly by id, where loading the whole cart to
 * find one row would be wasteful.
 */
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    /**
     * Finds a line by its own id <b>and</b> its owning cart.
     * <p>
     * The {@code cartId} half is a deliberate authorisation check, not a
     * convenience filter. An item id arrives from the client, so looking one up
     * by id alone would happily return a line out of somebody else's cart; this
     * makes the caller name the cart it believes the item belongs to, and an
     * empty result covers both "no such item" and "not yours" without telling
     * the caller which.
     */
    Optional<CartItem> findByIdAndCartId(UUID id, UUID cartId);
}
