package com.devicelk.cart.service;

import com.devicelk.cart.domain.Cart;
import com.devicelk.cart.repository.CartRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a new {@code ACTIVE} cart <b>in its own transaction</b>.
 * <p>
 * A separate bean for one reason, and it is not stylistic. Two simultaneous
 * first-time requests from the same user both find no cart and both insert; the
 * {@code ux_carts_one_active_per_user} partial unique index lets exactly one
 * win. The loser has to notice the collision and re-read the winner's cart —
 * but in PostgreSQL a constraint violation aborts the entire transaction, and
 * every subsequent statement on that connection fails with
 * {@code 25P02 current transaction is aborted} until it is rolled back. Catching
 * the exception inside the calling transaction and re-reading there would hit
 * that wall.
 * <p>
 * {@link Propagation#REQUIRES_NEW} gives the insert its own connection and its
 * own transaction, so a failure rolls that one back alone and leaves the caller's
 * transaction healthy enough to go and read the row the winner committed.
 * <p>
 * It also has to be a distinct bean rather than a method on the service: Spring's
 * transaction advice lives in a proxy, and a self-invocation would bypass it and
 * silently run in the caller's transaction — the exact thing this exists to avoid.
 */
@Component
class ActiveCartCreator {

    private final CartRepository cartRepository;

    ActiveCartCreator(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    /**
     * Inserts and commits a fresh {@code ACTIVE} cart for the user.
     * <p>
     * {@code saveAndFlush} rather than {@code save} so the INSERT — and any
     * constraint violation it causes — happens here, inside this transaction,
     * rather than being deferred to a flush in the caller's.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the user
     *         already has an {@code ACTIVE} cart, which under concurrency means
     *         a competing request committed one first
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Cart createActiveCart(String userId) {
        return cartRepository.saveAndFlush(new Cart(userId));
    }
}
