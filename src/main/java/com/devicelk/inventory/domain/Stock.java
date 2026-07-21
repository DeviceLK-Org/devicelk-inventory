package com.devicelk.inventory.domain;

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
 * product record is written rarely, whereas its quantities change on every sale
 * and restock. Keeping them apart means a stock movement never contends with a
 * catalogue edit, and the {@link Version optimistic lock} below guards only the
 * rows that actually need it.
 * <p>
 * The primary key is the owning product's id — assigned, never generated — so a
 * product and its stock share one identity and the row can be reached without a
 * secondary index.
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
     * Optimistic lock. Two concurrent adjustments to the same product would
     * otherwise interleave their read-modify-write and silently lose one of the
     * movements; with this column the losing transaction fails instead.
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
     * Lives on the entity rather than in the service so the "never negative"
     * invariant travels with the data it constrains — the database CHECK is the
     * backstop, not the first line of defence.
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
