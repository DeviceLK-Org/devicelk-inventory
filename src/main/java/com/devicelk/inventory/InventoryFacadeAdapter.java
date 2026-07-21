package com.devicelk.inventory;

import com.devicelk.inventory.service.ProductService;
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

    InventoryFacadeAdapter(ProductService productService) {
        this.productService = productService;
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
}
