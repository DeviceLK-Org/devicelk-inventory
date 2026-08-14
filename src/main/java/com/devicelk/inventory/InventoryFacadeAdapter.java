package com.devicelk.inventory;

import com.devicelk.inventory.service.ProductService;
import com.devicelk.inventory.service.StockReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Default {@link InventoryFacade}, a thin adapter over the module-internal
 * {@link ProductService}.
 * <p>
 * Package-private: callers in other modules bind to the interface, and only this
 * package can see the implementation. Delegating rather than reaching for the
 * repositories keeps every read on one path, so a future change to how a product
 * is assembled applies to inbound REST/gRPC traffic and to sibling modules alike.
 */
@Service
class InventoryFacadeAdapter implements InventoryFacade {

    private final ProductService productService;
    private final StockReservationService reservationService;

    InventoryFacadeAdapter(ProductService productService,
                           StockReservationService reservationService) {
        this.productService = productService;
        this.reservationService = reservationService;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSnapshot> getProduct(Long productId) {
        return productService.getProductSnapshot(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSnapshot> getProducts(List<Long> productIds) {
        return productService.getProductSnapshots(productIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean checkStock(Long productId, int quantity) {
        return productService.getProductSnapshot(productId)
                .map(snapshot -> snapshot.availableQty() >= quantity)
                .orElse(false);
    }

    // The reservation methods carry no @Transactional of their own, unlike the
    // reads above. That is not an omission. Each delegate is already
    // @Transactional with the default REQUIRED propagation, so it joins the
    // caller's checkout transaction — which is the entire point. Repeating the
    // annotation here would change nothing at runtime while implying this layer
    // is where the boundary starts, and the one thing a reader must not conclude
    // about a reservation is that it commits on its own.

    @Override
    public void reserveStock(List<ReservationLine> lines) {
        reservationService.reserve(lines);
    }

    @Override
    public void releaseStock(List<ReservationLine> lines) {
        reservationService.release(lines);
    }

    @Override
    public void confirmReservation(List<ReservationLine> lines) {
        reservationService.confirm(lines);
    }
}
