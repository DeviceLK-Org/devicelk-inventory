package com.devicelk.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;

import java.util.UUID;

/**
 * One line of a {@link Cart}: a product, a quantity, and the price it was
 * offered at. Mapped to {@code cart.cart_items}.
 * <p>
 * <b>Product reference is by value, not by key.</b> {@code productId} is a plain
 * {@code BIGINT} pointing at {@code inventory.products}, with no foreign key
 * across the schema boundary. A real FK would weld the two modules together at
 * the database level and block the eventual split into separate services — the
 * cost is that a deleted product can leave a dangling line, which the service
 * layer resolves on read rather than the database preventing outright.
 * <p>
 * <b>The price is a snapshot, deliberately.</b> {@code unitPriceCents} and
 * {@code currency} are copied from inventory when the item is added and never
 * refreshed. A cart that silently re-priced itself between two page loads would
 * be a worse bug than a stale price: checkout re-reads the live figure and is
 * the only place the difference gets reconciled.
 * <p>
 * The {@code (cart_id, product_id)} unique constraint is what makes "increment,
 * don't duplicate" an invariant rather than a convention — two concurrent adds
 * of the same product cannot both insert, whatever the service layer believes.
 */
@Entity
@Table(
        name = "cart_items",
        schema = "cart",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uc_cart_item_cart_product",
                        columnNames = {"cart_id", "product_id"}
                )
        }
)
// A zero or negative line is meaningless: removing an item is a DELETE, not a
// quantity of 0. Enforced in the database so it holds against any writer, not
// just the ones that run Bean Validation first.
@Check(name = "ck_cart_item_quantity_positive", constraints = "quantity > 0")
public class CartItem {

    /** Surrogate key; exposed to clients as the handle for update/delete of a line. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The owning cart. Lazy because the common traversal runs the other way —
     * loading a cart and walking its items — and an eager back-reference would
     * re-fetch the parent once per line.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "cart_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_cart_items_cart")
    )
    private Cart cart;

    /** Value reference to {@code inventory.products.id} — intentionally not a FK. */
    @NotNull(message = "Product id is required")
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Positive(message = "Quantity must be greater than zero")
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * Unit price in minor units, as inventory reported it at add-time.
     * <p>
     * {@code PositiveOrZero} rather than {@code Positive}: a promotional or
     * bundled line can legitimately be free, and rejecting that here would be
     * this module inventing a pricing rule that is not its to make.
     */
    @PositiveOrZero(message = "Unit price cannot be negative")
    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    /** ISO-4217 code for {@link #unitPriceCents}; the amount is unreadable without it. */
    @NotBlank(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO-4217 code")
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency;

    /** No-args constructor required by JPA. */
    protected CartItem() {
    }

    /**
     * Creates a line for the given product at the supplied snapshot price.
     * <p>
     * The cart back-reference is set by {@link Cart#addItem(CartItem)}, not here,
     * so there is one place that keeps both ends of the association consistent.
     */
    public CartItem(Long productId, int quantity, long unitPriceCents, String currency) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
        this.currency = currency;
    }

    /** Extended total for this line, in minor units. */
    public long lineTotalCents() {
        return unitPriceCents * quantity;
    }

    public UUID getId() {
        return id;
    }

    public Cart getCart() {
        return cart;
    }

    /** Package-private: the association is managed by {@link Cart}, not by callers. */
    void setCart(Cart cart) {
        this.cart = cart;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public void setUnitPriceCents(long unitPriceCents) {
        this.unitPriceCents = unitPriceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
