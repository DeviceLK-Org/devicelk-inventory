package com.devicelk.cart.api;

import com.devicelk.cart.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST API for the signed-in user's shopping cart.
 * <p>
 * <b>Every endpoint operates on the caller's own cart, and only that cart.</b>
 * The owner is taken from the {@code sub} claim of a JWT that Spring Security
 * has already validated against Keycloak — see {@link #subjectOf(Jwt)}. There is
 * no endpoint here that accepts a user id, and no request body carries one; the
 * URLs contain no user segment for a caller to tamper with. An {@code itemId} in
 * a path is not trusted either: the service resolves it inside the caller's cart,
 * so an id belonging to somebody else's basket comes back as a 404.
 * <p>
 * A thin adapter, as in the inventory module: it extracts the subject, delegates
 * to {@link CartService}, and hands the result to {@link CartResponseAssembler}.
 * No business logic and no entity ever reaches this layer's output.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;
    private final CartResponseAssembler assembler;

    public CartController(CartService cartService, CartResponseAssembler assembler) {
        this.cartService = cartService;
        this.assembler = assembler;
    }

    /**
     * Returns the caller's active cart, creating an empty one if they have none.
     *
     * @return HTTP 200 with the cart
     */
    @GetMapping
    public ResponseEntity<CartResponse> getCart(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(
                assembler.toResponse(cartService.getOrCreateActiveCart(subjectOf(jwt))));
    }

    /**
     * Adds units of a product to the caller's cart, incrementing the line if the
     * product is already in it.
     *
     * @return HTTP 201 with the updated cart
     */
    @PostMapping("/items")
    public ResponseEntity<CartResponse> addItem(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody AddItemRequest request) {
        CartResponse body = assembler.toResponse(cartService.addItem(
                subjectOf(jwt), request.productId(), request.quantity()));
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    /**
     * Sets a line to an absolute quantity; zero or less removes it.
     *
     * @return HTTP 200 with the updated cart
     */
    @PatchMapping("/items/{itemId}")
    public ResponseEntity<CartResponse> updateItem(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable UUID itemId,
                                                   @Valid @RequestBody UpdateQuantityRequest request) {
        return ResponseEntity.ok(assembler.toResponse(cartService.updateItemQuantity(
                subjectOf(jwt), itemId, request.quantity())));
    }

    /**
     * Removes a line from the caller's cart.
     *
     * @return HTTP 200 with the updated cart
     */
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<CartResponse> removeItem(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable UUID itemId) {
        return ResponseEntity.ok(
                assembler.toResponse(cartService.removeItem(subjectOf(jwt), itemId)));
    }

    /**
     * Empties the caller's cart, leaving the cart itself open.
     *
     * @return HTTP 200 with the now-empty cart
     */
    @DeleteMapping
    public ResponseEntity<CartResponse> clearCart(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(
                assembler.toResponse(cartService.clearCart(subjectOf(jwt))));
    }

    /**
     * Extracts the authenticated user's identity from their token.
     * <p>
     * The single place this application decides whose cart is being touched.
     * {@code sub} is Keycloak's immutable identifier for the user and is covered
     * by the token's signature, so it cannot be altered by the client — unlike
     * a username or email claim, which can change, or anything in a request the
     * caller composed.
     * <p>
     * The null guard is for the misconfiguration case, not the attack case: the
     * security filter chain rejects unauthenticated requests long before this
     * runs, so a null here means the cart routes were left unsecured, and it is
     * better to fail loudly than to quietly serve a cart owned by "null".
     */
    private static String subjectOf(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalStateException(
                    "No authenticated subject on a cart request — the cart endpoints "
                            + "are not covered by the security filter chain.");
        }
        return jwt.getSubject();
    }
}
