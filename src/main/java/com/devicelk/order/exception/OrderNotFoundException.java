package com.devicelk.order.exception;

import java.util.UUID;

/**
 * Raised when an order cannot be resolved <i>for the requesting user</i>.
 * <p>
 * <b>Covers two different situations on purpose:</b> the order does not exist,
 * and the order exists but belongs to somebody else. They are deliberately
 * indistinguishable from outside. A 403 for the second case would confirm that
 * the id is real and owned by another account, turning the endpoint into an
 * oracle for probing which order ids exist — so both answer 404, revealing
 * nothing the caller did not already supply.
 * <p>
 * The distinction is not merely hidden in the response, either: the query itself
 * filters on owner, so nothing in the service layer ever holds another user's
 * order to leak by accident. See {@code OrderRepository.findByIdAndUserId}.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("Order " + orderId + " not found.");
    }
}
