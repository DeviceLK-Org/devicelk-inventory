package com.devicelk.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Formatting helper for the integer-cents money representation used across the
 * inventory module.
 * <p>
 * Prices are stored and moved around as a {@code long} count of minor units
 * (cents) so no arithmetic ever touches a binary floating-point type. The only
 * place that count turns back into a human/wire-facing decimal string is
 * {@link #toDisplayString(long)} — every transport adapter (REST DTO, gRPC
 * response) must call it rather than re-deriving the conversion, so the two
 * contracts can never drift apart.
 */
public final class Money {

    /** Static-only helper; not instantiable. */
    private Money() {
    }

    /**
     * Renders a minor-unit amount as a fixed two-decimal string.
     * <p>
     * {@code BigDecimal.valueOf(cents, 2)} applies the scale exactly — without
     * division or rounding — and {@code toPlainString()} keeps the result free
     * of scientific notation for any magnitude.
     * <pre>
     *   toDisplayString(32999700) -> "329997.00"
     *   toDisplayString(0)        -> "0.00"
     *   toDisplayString(-250)     -> "-2.50"
     * </pre>
     *
     * @param cents the amount in minor units
     * @return the amount as a plain decimal string with exactly two decimals
     */
    public static String toDisplayString(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /**
     * Converts an inbound decimal amount to minor units, exactly.
     * <p>
     * The inverse of {@link #toDisplayString(long)}, and the only place a
     * caller-supplied decimal becomes cents. Rounding is deliberately refused
     * rather than applied: an amount carrying a fraction of a cent is a caller
     * error, and silently rounding it would let money change value on the way
     * into the system.
     * <pre>
     *   toCents(new BigDecimal("329997.00")) -> 32999700
     *   toCents(new BigDecimal("42999.99"))  -> 4299999
     *   toCents(new BigDecimal("100"))       -> 10000
     * </pre>
     *
     * @param amount the amount in major units
     * @return the equivalent amount in minor units
     * @throws ArithmeticException if the amount has more than two decimal
     *         places, or is too large to hold in a {@code long}
     */
    public static long toCents(BigDecimal amount) {
        return amount.movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }
}
