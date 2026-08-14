package com.devicelk.order.api;

import com.devicelk.inventory.InsufficientStockException;
import com.devicelk.order.exception.EmptyCartException;
import com.devicelk.order.exception.OrderNotFoundException;
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
 * Maps the order module's exceptions onto HTTP status codes.
 * <p>
 * <b>Scoped to this module, and ordered ahead of the inventory advice</b>, for
 * the same two reasons the cart's advice is:
 * <ul>
 *   <li>{@code basePackageClasses} confines it to controllers in the order
 *       module, so it cannot start silently handling exceptions thrown by cart
 *       or inventory endpoints;</li>
 *   <li>the inventory module's {@code GlobalExceptionHandler} is an unscoped
 *       {@code @RestControllerAdvice} and therefore applies application-wide. It
 *       maps {@code IllegalArgumentException} to 409, so without this advice
 *       taking precedence, order errors would inherit inventory's mapping.</li>
 * </ul>
 * {@link Ordered#HIGHEST_PRECEDENCE} settles the overlap explicitly rather than
 * leaving it to bean discovery order, which is not something to depend on.
 * <p>
 * Cart's advice is untouched and unaffected: it is scoped to
 * {@code CartController}'s package, and this one to {@code OrderController}'s, so
 * the two never see each other's exceptions despite sharing a precedence.
 * <p>
 * The response body matches the shape the inventory and cart handlers already
 * return, so a client parsing errors from this application sees one format.
 */
@RestControllerAdvice(basePackageClasses = OrderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OrderExceptionHandler {

    /**
     * Checkout was attempted with nothing to buy.
     * <p>
     * 400 rather than 409: a conflict implies the request could succeed once the
     * world changes, but this one cannot succeed until the <i>caller</i> does
     * something different — put an item in the basket. The request is the problem.
     */
    @ExceptionHandler(EmptyCartException.class)
    ResponseEntity<Map<String, Object>> handleEmptyCart(EmptyCartException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    /**
     * Inventory could not hold the units for one of the basket's lines.
     * <p>
     * <b>This is inventory's published exception, not the cart module's.</b> The
     * two are distinct types with the same name: the cart's is thrown while
     * browsing, when nothing is at stake, and is handled by the cart's own
     * advice. This one comes from {@code InventoryFacade.reserveStock} at the
     * moment units are claimed, and by the time it arrives here the entire
     * checkout transaction has already rolled back — no order, no stock movement,
     * and the customer's cart still intact and ACTIVE.
     * <p>
     * 409: the request is well-formed and the client did nothing wrong. It
     * conflicts with the state of the world, and may well succeed later.
     */
    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    /**
     * The order is not this user's — either it does not exist, or it belongs to
     * someone else.
     * <p>
     * 404 for both, deliberately. A 403 would confirm that the id is real and
     * owned by another account, turning the endpoint into an oracle for
     * enumerating order ids; "not found" reveals nothing the caller did not
     * already supply.
     */
    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
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
