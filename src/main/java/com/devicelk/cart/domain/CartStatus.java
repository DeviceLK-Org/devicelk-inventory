package com.devicelk.cart.domain;

/**
 * Lifecycle states of a {@link Cart}.
 * <p>
 * Persisted as the enum's {@code String} name rather than its ordinal, keeping
 * the column readable and immune to reordering — the same convention the
 * inventory module applies to its category column.
 */
public enum CartStatus {

    /** Open and mutable. A user has at most one cart in this state. */
    ACTIVE,

    /**
     * Converted into an order. Terminal: the cart is kept rather than deleted so
     * the order retains the basket it was placed from.
     */
    CHECKED_OUT,

    /** Left untouched long enough to be retired. Terminal. */
    ABANDONED
}
