package com.devicelk.cart.api;

import com.devicelk.cart.exception.CartItemNotFoundException;
import com.devicelk.cart.exception.InsufficientStockException;
import com.devicelk.cart.exception.UnknownProductException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the cart module's exceptions onto HTTP status codes.
 * <p>
 * <b>Scoped to this module, and ordered ahead of the inventory advice.</b> Two
 * things make that necessary rather than tidy:
 * <ul>
 *   <li>{@code basePackageClasses} confines this advice to controllers in the
 *       cart module, so it cannot start silently handling exceptions thrown by
 *       inventory — the mirror of the problem described below.</li>
 *   <li>The inventory module's {@code GlobalExceptionHandler} is an unscoped
 *       {@code @RestControllerAdvice}, which means it applies application-wide
 *       despite living inside another module's internals. It maps
 *       {@code IllegalArgumentException} to 409, so without this advice taking
 *       precedence a rejected cart quantity would surface as a Conflict instead
 *       of a Bad Request.</li>
 * </ul>
 * {@link Ordered#HIGHEST_PRECEDENCE} settles the overlap explicitly rather than
 * leaving it to bean discovery order, which is not something to depend on.
 * <p>
 * The response body matches the shape the inventory handler already returns, so
 * a client parsing errors from this application sees one format.
 */
@RestControllerAdvice(basePackageClasses = CartController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class CartExceptionHandler {

    /**
     * A product the cart was asked to work with does not exist in inventory.
     * <p>
     * 404 rather than 400: the request named a resource, and the resource is not
     * there. That the id arrived in a body rather than a path does not change
     * what went wrong.
     */
    @ExceptionHandler(UnknownProductException.class)
    ResponseEntity<Map<String, Object>> handleUnknownProduct(UnknownProductException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    /**
     * The line is not in this user's cart — either it does not exist, or it
     * belongs to someone else.
     * <p>
     * 404 for both, deliberately. A 403 would confirm that the id is real and
     * owned by another account, turning the endpoint into an oracle for
     * enumerating item ids; "not found" reveals nothing the caller did not
     * already supply.
     */
    @ExceptionHandler(CartItemNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleItemNotFound(CartItemNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    /**
     * Inventory cannot currently satisfy the requested quantity.
     * <p>
     * 409: the request is well-formed and the client did nothing wrong — it
     * conflicts with the state of the world, and may well succeed later.
     */
    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    /**
     * A malformed argument that reached the service layer — a non-positive
     * add quantity being the case that matters.
     * <p>
     * 400, overriding the 409 the inventory advice would otherwise apply. The
     * caller sent something invalid; there is no state on the server that could
     * change to make the same request valid.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status,
                                                               String error,
                                                               String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return new ResponseEntity<>(body, status);
    }
}
