package com.devicelk.cart.api;

import java.util.List;
import java.util.UUID;

/**
 * A cart as returned to an API client.
 * <p>
 * Money is sent as integer minor units, never as a formatted string or a
 * floating-point number. The client formats for display; this keeps one
 * representation authoritative and means no amount is ever parsed back out of
 * text it was rendered into. The cart module cannot reuse inventory's
 * {@code Money} helper in any case — that type is internal to inventory — and
 * duplicating it here to emit a string nobody needs would be the worse trade.
 * <p>
 * The owner's id is absent on purpose: the response goes to the only person
 * entitled to it, and echoing a subject id back adds nothing a client does not
 * already hold in its own token.
 *
 * @param cartId     the cart's id
 * @param status     lifecycle state; {@code ACTIVE} for anything a client sees here
 * @param items      the basket lines
 * @param totalCents sum of every line total, in minor units
 * @param currency   ISO-4217 code the total is denominated in; {@code null} for
 *                   an empty cart, which has no currency to speak of
 */
public record CartResponse(
        UUID cartId,
        String status,
        List<CartItemResponse> items,
        long totalCents,
        String currency
) {
}
