package com.devicelk.order;

/**
 * Lifecycle state of an {@code Order}.
 * <p>
 * <b>Lives in the module's base package, not in {@code domain}, because it is
 * part of the published language.</b> An order's state is the one fact other
 * modules genuinely need about it — a payment or notification module reacting to
 * an order has to be able to say "is this PAID". Keeping the enum internal would
 * force {@link OrderSnapshot} to carry the status as a {@code String}, which
 * trades a compiler-checked value for one that is spelled correctly by
 * convention and silently wrong the day somebody types {@code "Paid"}.
 * <p>
 * The {@code domain} entity referencing a base-package type of its own module is
 * not a boundary violation: Modulith constrains what <i>other</i> modules may
 * reach, and this is exactly the direction that is allowed.
 * <p>
 * Persisted by {@code name()} via {@code EnumType.STRING}, so the database holds
 * {@code 'PENDING'} rather than an ordinal. Ordinals make the column unreadable
 * and turn any future reordering of these constants into silent data corruption.
 * Constants may therefore be added freely, but never renamed or removed without
 * a migration.
 */
public enum OrderStatus {

    /**
     * Placed, stock reserved, not yet paid for.
     * <p>
     * The state every order is born in. Note what it already implies: by the time
     * an order exists at all, checkout has committed a stock reservation for its
     * lines, so a PENDING order is holding units away from other customers.
     */
    PENDING,

    /** Payment settled. The reservation has been consumed by the sale. */
    PAID,

    /** Payment attempted and refused. Reserved units are owed back to inventory. */
    FAILED,

    /** Withdrawn before payment. Reserved units are owed back to inventory. */
    CANCELLED
}
