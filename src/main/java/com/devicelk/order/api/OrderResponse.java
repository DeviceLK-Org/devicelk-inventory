package com.devicelk.order.api;

import com.devicelk.order.service.OrderData;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as returned to an API client.
 * <p>
 * Money is sent as integer minor units, never as a formatted string or a
 * floating-point number — the same rule the cart's response follows, so a client
 * parses one representation of money across this whole API.
 * <p>
 * The owner's id is absent on purpose: every one of these endpoints serves the
 * caller their own orders, so echoing a subject id back adds nothing a client
 * does not already hold in its own token.
 * <p>
 * <b>Mapped by a static factory rather than an assembler component</b>, which is
 * where this departs from the cart module. {@code CartResponseAssembler} is a
 * bean because it has to call {@link com.devicelk.inventory.InventoryFacade} to
 * resolve product names a cart line does not store. An order line stores its
 * name, so this mapping needs nothing injected and is a pure function of its
 * input — making it a bean would add a dependency to the controller to
 * communicate nothing.
 *
 * @param orderId    the order's id
 * @param status     lifecycle state; {@code PENDING} for a freshly placed order
 * @param items      the order lines; never empty
 * @param totalCents order total in minor units, as stored at checkout
 * @param currency   ISO-4217 code the total is denominated in
 * @param createdAt  when the order was placed
 */
public record OrderResponse(
        UUID orderId,
        String status,
        List<OrderItemResponse> items,
        long totalCents,
        String currency,
        Instant createdAt
) {

    /**
     * Renders a service-layer order for the wire.
     * <p>
     * The total is taken from {@link OrderData#totalCents()} — the figure stored
     * on the order row — rather than re-summed from the lines here. Recomputing
     * it would risk showing a customer a number that differs from the one their
     * order was recorded at, which is the exact drift storing the total was meant
     * to rule out.
     */
    public static OrderResponse from(OrderData order) {
        return new OrderResponse(
                order.orderId(),
                order.status().name(),
                order.items().stream().map(OrderItemResponse::from).toList(),
                order.totalCents(),
                order.currency(),
                order.placedAt());
    }
}
