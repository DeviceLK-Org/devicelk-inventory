package com.devicelk.cart.service;

import com.devicelk.cart.domain.Cart;
import com.devicelk.cart.domain.CartItem;
import com.devicelk.cart.domain.CartStatus;
import com.devicelk.cart.exception.CartItemNotFoundException;
import com.devicelk.cart.exception.InsufficientStockException;
import com.devicelk.cart.exception.UnknownProductException;
import com.devicelk.cart.repository.CartItemRepository;
import com.devicelk.cart.repository.CartRepository;
import com.devicelk.inventory.InventoryFacade;
import com.devicelk.inventory.ProductSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link CartService}.
 * <p>
 * Package-private: callers bind to the interface, so the implementation stays an
 * internal detail of the cart module.
 * <p>
 * {@link InventoryFacade} is the module's <b>only</b> door into inventory —
 * no entity, repository or service of that module is reachable from here, and
 * {@code ModularityTests} fails the build if that ever changes. The facade is
 * used for reads alone: this class never asks inventory to move stock.
 */
@Service
class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final InventoryFacade inventoryFacade;
    private final ActiveCartCreator activeCartCreator;

    CartServiceImpl(CartRepository cartRepository,
                    CartItemRepository cartItemRepository,
                    InventoryFacade inventoryFacade,
                    ActiveCartCreator activeCartCreator) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.inventoryFacade = inventoryFacade;
        this.activeCartCreator = activeCartCreator;
    }

    @Override
    @Transactional
    public CartData getOrCreateActiveCart(String userId) {
        return toData(loadOrCreateActiveCart(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CartData> findActiveCart(String userId) {
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .map(CartServiceImpl::toData);
    }

    @Override
    @Transactional
    public CartData addItem(String userId, Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Quantity to add must be greater than zero, was " + quantity + ".");
        }

        Cart cart = loadOrCreateActiveCart(userId);

        // One read covers all three questions: does the product exist, is there
        // enough of it, and what does it cost. Asking checkStock as well would
        // re-read the same rows and straddle any concurrent stock movement.
        ProductSnapshot snapshot = inventoryFacade.getProduct(productId)
                .orElseThrow(() -> new UnknownProductException(productId));

        CartItem existing = findLine(cart, productId);

        // The check is against the resulting total, not the increment — adding 3
        // to a line of 8 has to be satisfiable as 11, not as 3.
        int newQuantity = existing == null ? quantity : existing.getQuantity() + quantity;
        requireStock(snapshot, newQuantity);

        if (existing == null) {
            cart.addItem(new CartItem(
                    productId, quantity, snapshot.priceCents(), snapshot.currency()));
        } else {
            // Quantity only. The line keeps the price it was created at, so a
            // customer topping up a basket is not silently re-quoted.
            existing.setQuantity(newQuantity);
        }

        return saveAndReturn(cart);
    }

    @Override
    @Transactional
    public CartData updateItemQuantity(String userId, UUID itemId, int newQuantity) {
        Cart cart = requireActiveCart(userId, itemId);
        CartItem item = requireOwnedItem(cart, itemId);

        if (newQuantity <= 0) {
            cart.removeItem(item);
            return saveAndReturn(cart);
        }

        ProductSnapshot snapshot = inventoryFacade.getProduct(item.getProductId())
                .orElseThrow(() -> new UnknownProductException(item.getProductId()));
        requireStock(snapshot, newQuantity);

        item.setQuantity(newQuantity);
        return saveAndReturn(cart);
    }

    @Override
    @Transactional
    public CartData removeItem(String userId, UUID itemId) {
        Cart cart = requireActiveCart(userId, itemId);
        cart.removeItem(requireOwnedItem(cart, itemId));
        return saveAndReturn(cart);
    }

    @Override
    @Transactional
    public CartData clearCart(String userId) {
        Cart cart = loadOrCreateActiveCart(userId);
        cart.clearItems();
        return saveAndReturn(cart);
    }

    @Override
    @Transactional
    public void markCheckedOut(UUID cartId) {
        Cart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new IllegalArgumentException("No cart with id " + cartId));
        cart.markCheckedOut();
        cartRepository.save(cart);
    }

    /**
     * Returns the user's {@code ACTIVE} cart, creating one if they have none.
     * <p>
     * The create path is racy by nature — check-then-act across two requests —
     * and is resolved by the database rather than by hoping the window is small:
     * the partial unique index rejects the second insert, and the loser reads the
     * winner's cart instead. That re-read sees the committed row because
     * PostgreSQL's default READ COMMITTED takes a fresh snapshot per statement;
     * under REPEATABLE READ this would need rethinking.
     */
    private Cart loadOrCreateActiveCart(String userId) {
        Optional<Cart> existing = cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return activeCartCreator.createActiveCart(userId);
        } catch (DataIntegrityViolationException e) {
            // Lost the race. Someone else's cart is now the user's active cart,
            // which is the outcome this method promised either way.
            log.debug("Concurrent cart creation for user {}; re-reading the winner's cart", userId);
            return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Loads the user's active cart for an operation that names a line.
     * <p>
     * A user with no cart cannot own the item they are asking about, so this
     * reports the item as not found rather than that the cart is missing —
     * keeping every "not yours" answer identical regardless of which part of the
     * lookup failed.
     */
    private Cart requireActiveCart(String userId, UUID itemId) {
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseThrow(() -> new CartItemNotFoundException(itemId));
    }

    /**
     * Resolves a line <b>within</b> the given cart.
     * <p>
     * The authorisation check. The item id arrives from the client, so it is
     * looked up by id <i>and</i> cart id: an id belonging to another user's cart
     * simply does not match, and comes back as not-found without this code ever
     * needing to compare owners.
     * <p>
     * The returned instance is the same object the cart already holds — JPA
     * guarantees one instance per row per persistence context — so removing it
     * from the cart's collection works on identity as expected.
     */
    private CartItem requireOwnedItem(Cart cart, UUID itemId) {
        return cartItemRepository.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new CartItemNotFoundException(itemId));
    }

    /** Rejects a quantity the product cannot currently satisfy. */
    private static void requireStock(ProductSnapshot snapshot, int wanted) {
        if (snapshot.availableQty() < wanted) {
            throw new InsufficientStockException(
                    snapshot.productId(), wanted, snapshot.availableQty());
        }
    }

    /** Finds the existing line for a product, or {@code null} if there is none. */
    private static CartItem findLine(Cart cart, Long productId) {
        return cart.getItems().stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Persists a cart whose items changed and returns the new view.
     * <p>
     * {@code touch()} is what makes {@code updated_at} honest. Hibernate refreshes
     * it only when the cart row itself is dirty, and adding or removing a line
     * dirties the child, not the parent — without this the column would quietly
     * mean "header last changed" while every reader assumes it means the cart did.
     */
    private CartData saveAndReturn(Cart cart) {
        cart.touch();
        return toData(cartRepository.save(cart));
    }

    private static CartData toData(Cart cart) {
        return new CartData(
                cart.getId(),
                cart.getUserId(),
                cart.getStatus(),
                cart.getItems().stream().map(CartServiceImpl::toItemData).toList());
    }

    private static CartItemData toItemData(CartItem item) {
        return new CartItemData(
                item.getId(),
                item.getProductId(),
                item.getQuantity(),
                item.getUnitPriceCents(),
                item.getCurrency());
    }
}
