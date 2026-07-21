package com.devicelk.cart.api;

import com.devicelk.cart.service.CartData;
import com.devicelk.cart.service.CartItemData;
import com.devicelk.inventory.InventoryFacade;
import com.devicelk.inventory.ProductSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns the service layer's {@link CartData} into the client-facing
 * {@link CartResponse}.
 * <p>
 * Exists as its own component for one reason: product names are not stored on
 * cart lines — only {@code product_id} is, deliberately, so the cart never holds
 * a stale copy of inventory's catalogue — which means rendering a cart requires
 * asking inventory for them. Doing that per line would be an N+1 against another
 * module, so every name for a cart is fetched in <b>one</b>
 * {@link InventoryFacade#getProducts(List)} call, however many lines there are.
 * <p>
 * Package-private: an implementation detail of the cart's REST adapter.
 */
@Component
class CartResponseAssembler {

    private final InventoryFacade inventoryFacade;

    CartResponseAssembler(InventoryFacade inventoryFacade) {
        this.inventoryFacade = inventoryFacade;
    }

    CartResponse toResponse(CartData cart) {
        List<Long> productIds = cart.items().stream()
                .map(CartItemData::productId)
                .distinct()
                .toList();

        // One call. Not one per line.
        Map<Long, ProductSnapshot> byProductId = inventoryFacade.getProducts(productIds).stream()
                .collect(Collectors.toMap(ProductSnapshot::productId, Function.identity()));

        List<CartItemResponse> items = cart.items().stream()
                .map(item -> toItemResponse(item, byProductId.get(item.productId())))
                .toList();

        return new CartResponse(
                cart.cartId(),
                cart.status().name(),
                items,
                cart.totalCents(),
                resolveCurrency(cart));
    }

    /**
     * @param product the line's product, or {@code null} if inventory no longer
     *                has it — the line still renders, with a null name, because
     *                a cart the user cannot even look at is a worse outcome than
     *                one with a gap in it
     */
    private static CartItemResponse toItemResponse(CartItemData item, ProductSnapshot product) {
        return new CartItemResponse(
                item.itemId(),
                item.productId(),
                product == null ? null : product.name(),
                item.quantity(),
                item.unitPriceCents(),
                item.currency(),
                item.lineTotalCents());
    }

    /**
     * Determines the currency the cart total is expressed in.
     * <p>
     * Summing lines only means something if they share a currency. Today they
     * always do — everything is LKR — but that is a fact about the data, not a
     * guarantee of the schema, so it is checked rather than assumed. A mixed
     * cart has no honest total, and reporting one anyway would produce a number
     * that looks authoritative and is silently wrong.
     *
     * @return the single currency in use, or {@code null} for an empty cart
     * @throws IllegalStateException if lines disagree — a data fault the API
     *         cannot faithfully represent, so it surfaces rather than hides
     */
    private static String resolveCurrency(CartData cart) {
        List<String> distinct = cart.items().stream()
                .map(CartItemData::currency)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return null;
        }
        if (distinct.size() > 1) {
            throw new IllegalStateException(
                    "Cart " + cart.cartId() + " mixes currencies " + distinct
                            + "; its total cannot be expressed as a single amount.");
        }
        return distinct.get(0);
    }
}
