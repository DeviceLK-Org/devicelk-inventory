package com.devicelk.cart.repository;

import com.devicelk.cart.domain.Cart;
import com.devicelk.cart.domain.CartStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence gateway for {@link Cart} aggregates.
 * <p>
 * Module-internal: it sits under {@code cart.repository}, which Spring Modulith
 * treats as private to this module, so no sibling module can reach the data
 * except through {@code CartFacade}. Java visibility has to stay {@code public}
 * for Spring Data to proxy the interface and for the service package to inject
 * it — the boundary is enforced by {@code ModularityTests}, not by the compiler.
 */
public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * Finds the user's cart in the given status, with its items already loaded.
     * <p>
     * The entity graph collapses what would otherwise be a cart query followed
     * by a second query for the item collection. Every caller of this method
     * goes on to read the items, so fetching them lazily buys nothing and costs
     * a round trip.
     * <p>
     * Returns at most one row by convention rather than by constraint: a user is
     * meant to hold a single {@link CartStatus#ACTIVE} cart, but nothing in the
     * schema enforces that yet (see the note on a partial unique index). A
     * duplicate would surface here as an
     * {@code IncorrectResultSizeDataAccessException}.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Cart> findByUserIdAndStatus(String userId, CartStatus status);

    /**
     * Loads a cart by id with its items, for operations that already know which
     * cart they are working on.
     * <p>
     * Ownership is <b>not</b> checked here. Every caller must confirm the cart
     * belongs to the authenticated user before acting on what this returns.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Cart> findWithItemsById(UUID id);
}
