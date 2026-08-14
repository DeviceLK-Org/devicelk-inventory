package com.devicelk.order;

import com.devicelk.order.service.OrderData;
import com.devicelk.order.service.OrderItemData;
import com.devicelk.order.service.OrderService;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link OrderFacade}, a thin adapter over the module-internal
 * {@link OrderService}.
 * <p>
 * Package-private: other modules bind to the interface and only this package can
 * see the implementation.
 * <p>
 * Delegates rather than reaching for the repository, so an order read by a
 * listener travels the same code path as one read by the order module's own REST
 * endpoints. Two paths to the same data would eventually disagree, and the one
 * that skipped the service is the one that would be missing a rule.
 * <p>
 * Transaction boundaries live on {@code OrderService}; adding another layer of
 * {@code @Transactional} here would only obscure where they actually start.
 */
@Service
class OrderFacadeAdapter implements OrderFacade {

    private final OrderService orderService;

    OrderFacadeAdapter(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public Optional<OrderSnapshot> getOrder(UUID orderId) {
        return orderService.findOrder(orderId).map(OrderFacadeAdapter::toSnapshot);
    }

    /** Narrows the service's view to the subset other modules are given. */
    private static OrderSnapshot toSnapshot(OrderData order) {
        return new OrderSnapshot(
                order.orderId(),
                order.userId(),
                order.status(),
                order.totalCents(),
                order.currency(),
                order.placedAt(),
                order.items().stream()
                        .map(OrderFacadeAdapter::toItemSnapshot)
                        .toList());
    }

    private static OrderItemSnapshot toItemSnapshot(OrderItemData item) {
        return new OrderItemSnapshot(
                item.productId(),
                item.productName(),
                item.quantity(),
                item.unitPriceCents(),
                item.currency());
    }
}
