package com.devicelk.inventory.domain;

import com.devicelk.inventory.InsufficientStockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Min;

/**
 * On-hand quantities for a single {@link Product}, mapped to
 * {@code inventory.stock}.
 * <p>
 * Split out of {@code Product} because the two have opposite access profiles: a
 * product is written rarely, its quantities change on every sale and restock.
 * Keeping them apart means a stock movement never contends with a catalogue
 * edit, and the {@link Version optimistic lock} guards only the rows that need it.
 * <p>
 * The primary key is the owning product's id, assigned rather than generated, so
 * the row is reachable without a secondary index.
 */
@Entity
@Table(name = "stock", schema = "inventory")
public class Stock {

    /** Owning product's id: both the primary key and the FK to {@code products}. */
    @Id
    @Column(name = "product_id")
    private Long productId;

    /** Units on hand and sellable. The database additionally enforces {@code >= 0}. */
    @Min(value = 0, message = "Available quantity cannot be negative")
    @Column(name = "available_qty", nullable = false)
    private int availableQty;

    /** Units held for in-flight orders — on hand, but not sellable. */
    @Min(value = 0, message = "Reserved quantity cannot be negative")
    @Column(name = "reserved_qty", nullable = false)
    private int reservedQty;

    /**
     * Re-order trigger level. When {@link #availableQty} drops to or below this
     * value the service layer raises a low-stock alert — see {@link #isLowStock()}.
     */
    @Min(value = 0, message = "Minimum stock threshold cannot be negative")
    @Column(name = "min_stock_threshold", nullable = false)
    private int minStockThreshold;

    /**
     * Optimistic lock. Without it, two concurrent adjustments would interleave
     * their read-modify-write and silently lose one movement; with it, the losing
     * transaction fails instead.
     */
    @Version
    @Column(nullable = false)
    private long version;

    /** No-args constructor required by JPA. */
    public Stock() {
    }

    /** Creates the stock row that accompanies a newly created product. */
    public Stock(Long productId, int availableQty, int minStockThreshold) {
        this.productId = productId;
        this.availableQty = availableQty;
        this.minStockThreshold = minStockThreshold;
        this.reservedQty = 0;
    }

    /**
     * Applies a signed movement to the available quantity.
     * <p>
     * On the entity rather than in the service so the "never negative" invariant
     * travels with the data; the database CHECK is the backstop.
     *
     * @param quantityChange signed delta; positive restocks, negative consumes
     * @throws IllegalArgumentException when the movement would drive the
     *         available quantity below zero (surfaces as HTTP 409)
     */
    public void adjustAvailableQty(int quantityChange) {
        int newQty = availableQty + quantityChange;
        if (newQty < 0) {
            throw new IllegalArgumentException("Insufficient stock!");
        }
        this.availableQty = newQty;
    }

    /**
     * Holds units back for an in-flight order: available → reserved.
     * <p>
     * A move, not a decrement. Total units on hand (the sum of both counts) is
     * unchanged, because nothing has physically left the warehouse — that happens
     * at {@link #confirmReserved(int)}. Decrementing availability alone would
     * leave {@link #release(int)} nothing to give back.
     *
     * @param quantity units to hold; must be positive
     * @throws IllegalArgumentException   if {@code quantity} is not positive,
     *                                    which would run the transfer backwards
     * @throws InsufficientStockException if fewer than {@code quantity} units are
     *                                    available to hold
     */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Reservation quantity must be positive, got " + quantity
                            + " for product " + productId + ".");
        }
        if (availableQty < quantity) {
            throw new InsufficientStockException(productId, quantity, availableQty);
        }
        this.availableQty -= quantity;
        this.reservedQty += quantity;
    }

    /**
     * Returns held units to the shelf: reserved → available.
     * <p>
     * The compensating action for {@link #reserve(int)} — a cancelled order, a
     * refused payment, or a checkout that failed after its reservation committed.
     * <p>
     * Releasing more than is held is rejected rather than clamped: clamping would
     * let a double-compensation manufacture stock, and the resulting oversell
     * would surface far from its cause.
     *
     * @param quantity units to return; must be positive
     * @throws IllegalArgumentException if {@code quantity} is not positive
     * @throws IllegalStateException    if fewer than {@code quantity} units are
     *                                  currently reserved
     */
    public void release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Release quantity must be positive, got " + quantity
                            + " for product " + productId + ".");
        }
        if (reservedQty < quantity) {
            throw new IllegalStateException(
                    "Cannot release " + quantity + " units of product " + productId
                            + ": only " + reservedQty + " are reserved.");
        }
        this.reservedQty -= quantity;
        this.availableQty += quantity;
    }

    /**
     * Consumes held units: the sale completed and the goods left the warehouse.
     * <p>
     * Only {@link #reservedQty} falls. {@link #availableQty} was already reduced
     * by {@link #reserve(int)}; touching it again would charge the same units
     * twice and cause phantom oversell.
     *
     * @param quantity units to consume; must be positive
     * @throws IllegalArgumentException if {@code quantity} is not positive
     * @throws IllegalStateException    if fewer than {@code quantity} units are
     *                                  currently reserved
     */
    public void confirmReserved(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Confirmation quantity must be positive, got " + quantity
                            + " for product " + productId + ".");
        }
        if (reservedQty < quantity) {
            throw new IllegalStateException(
                    "Cannot confirm " + quantity + " units of product " + productId
                            + ": only " + reservedQty + " are reserved.");
        }
        this.reservedQty -= quantity;
    }

    /** Whether the available quantity has reached or fallen below the re-order level. */
    public boolean isLowStock() {
        return availableQty <= minStockThreshold;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public int getAvailableQty() {
        return availableQty;
    }

    public void setAvailableQty(int availableQty) {
        this.availableQty = availableQty;
    }

    public int getReservedQty() {
        return reservedQty;
    }

    public void setReservedQty(int reservedQty) {
        this.reservedQty = reservedQty;
    }

    public int getMinStockThreshold() {
        return minStockThreshold;
    }

    public void setMinStockThreshold(int minStockThreshold) {
        this.minStockThreshold = minStockThreshold;
    }

    public long getVersion() {
        return version;
    }
}
