package com.devicelk.order.service;

import com.devicelk.cart.CartFacade;
import com.devicelk.cart.CartItemSnapshot;
import com.devicelk.cart.CartSnapshot;
import com.devicelk.inventory.InventoryFacade;
import com.devicelk.inventory.ProductSnapshot;
import com.devicelk.inventory.ReservationLine;
import com.devicelk.order.OrderItemSnapshot;
import com.devicelk.order.OrderPlacedEvent;
import com.devicelk.order.domain.Order;
import com.devicelk.order.domain.OrderItem;
import com.devicelk.order.exception.EmptyCartException;
import com.devicelk.order.exception.OrderNotFoundException;
import com.devicelk.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link OrderService}.
 * <p>
 * Package-private: callers bind to the interface, and only this package sees the
 * implementation.
 * <p>
 * Reaches the cart and inventory modules exclusively through {@link CartFacade}
 * and {@link InventoryFacade}. No repository, entity or service from either
 * module is imported here, which is what {@code ModularityTests} checks and what
 * makes the eventual split into services a change of transport rather than a
 * rewrite.
 */
@Service
class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final OrderRepository orderRepository;
    private final CartFacade cartFacade;
    private final InventoryFacade inventoryFacade;
    private final ApplicationEventPublisher events;

    OrderServiceImpl(OrderRepository orderRepository,
                     CartFacade cartFacade,
                     InventoryFacade inventoryFacade,
                     ApplicationEventPublisher events) {
        this.orderRepository = orderRepository;
        this.cartFacade = cartFacade;
        this.inventoryFacade = inventoryFacade;
        this.events = events;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>One transaction, five steps, all of them or none.</b> That is the whole
     * design, and every choice below defends it:
     * <ol>
     *   <li>read the user's active cart;</li>
     *   <li>reserve the stock for its lines;</li>
     *   <li>write the order, snapshotting what was bought;</li>
     *   <li>close the cart;</li>
     *   <li>announce the order.</li>
     * </ol>
     * <p>
     * <b>Nothing in this path may use {@code REQUIRES_NEW}</b>, and the facades
     * it calls are written not to. The failure it prevents is specific: a
     * reservation that committed on its own would survive the rollback of the
     * order it was made for, leaving units held for a purchase that does not
     * exist — invisible to every report, releasable by nobody, and gone from the
     * shelf until someone notices the arithmetic is wrong. The same applies to
     * the cart closure: committed independently, it would strand a basket the
     * customer never got an order for. Because all five share one transaction,
     * an exception anywhere — including the {@code InsufficientStockException}
     * from step 2 — unwinds the lot, and the database is left exactly as it was.
     * <p>
     * <b>This works because it is still a monolith.</b> Steps 2, 3 and 4 write to
     * three schemas in one database, so a single transaction genuinely spans
     * them. When the order module becomes its own service that stops being true,
     * and step 2 becomes the first leg of a saga: reserve over the wire, and on
     * any later failure issue a compensating
     * {@link InventoryFacade#releaseStock} rather than relying on a rollback that
     * no longer reaches across the boundary. {@code releaseStock} already exists
     * for that day. What cannot be carried over is the atomicity itself — the
     * saga is eventually consistent, and the window between reserving and
     * compensating is real. Keeping the reservation call behind the facade is
     * what makes that a change to one line here.
     */
    @Override
    @Transactional
    public OrderData checkout(String userId) {
        // 1. The basket, read inside this transaction. An empty or absent cart is
        //    rejected before anything moves — there is nothing to buy, and the
        //    NOT NULL currency on Order would reject a line-less order anyway.
        CartSnapshot cart = cartFacade.getActiveCart(userId)
                .orElseThrow(EmptyCartException::new);
        if (cart.items().isEmpty()) {
            throw new EmptyCartException();
        }

        // 2. Claim the stock. This both checks and holds, atomically — checking
        //    first with checkStock would only add a read whose answer this call
        //    immediately supersedes. Insufficient stock throws, and because that
        //    exception leaves this @Transactional method, the transaction is
        //    marked rollback-only: no order row, no cart change, no units moved.
        List<ReservationLine> reservations = cart.items().stream()
                .map(item -> new ReservationLine(item.productId(), item.quantity()))
                .toList();
        inventoryFacade.reserveStock(reservations);

        // 3. Record the purchase. Quantity, unit price and currency come from the
        //    cart — the figures the customer was actually shown — while the name
        //    is read from inventory, because a cart line does not carry one.
        Map<Long, String> productNames = productNamesFor(cart.items());
        Order order = new Order(userId);
        for (CartItemSnapshot item : cart.items()) {
            order.addItem(new OrderItem(
                    item.productId(),
                    productNameOrFail(productNames, item.productId()),
                    item.quantity(),
                    item.unitPriceCents(),
                    item.currency()));
        }
        // The total is maintained by Order.addItem as the lines go in, so the
        // stored figure cannot disagree with what it is a total of.
        //
        // saveAndFlush, not save: @CreationTimestamp is a before-execution
        // generator, so createdAt is populated when the INSERT actually runs
        // rather than when the entity is persisted. Without forcing the flush
        // here, the response — and the event — would carry a null placedAt for an
        // order the database has a perfectly good timestamp for. Flushing also
        // brings any constraint violation forward into this method, where it can
        // be reasoned about, instead of surfacing at commit.
        Order placed = orderRepository.saveAndFlush(order);

        // 4. Close the basket, freeing the user to start a new one. Keyed by the
        //    cart id read in step 1, so this closes the cart that was actually
        //    ordered from rather than whatever is active by now.
        cartFacade.markCheckedOut(cart.cartId());

        // 5. Announce it. Published inside the transaction but delivered after
        //    commit, so no listener can observe an order that later rolls back.
        OrderData data = toData(placed);
        events.publishEvent(new OrderPlacedEvent(
                data.orderId(),
                data.userId(),
                data.totalCents(),
                data.currency(),
                data.items().stream().map(OrderServiceImpl::toItemSnapshot).toList()));

        log.info("Checkout complete: order {} for user {} ({} line(s), total {} {})",
                data.orderId(), userId, data.items().size(),
                data.totalCents(), data.currency());
        return data;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderData> findOrder(UUID orderId) {
        return orderRepository.findById(orderId).map(OrderServiceImpl::toData);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderData> findOrdersForUser(String userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(OrderServiceImpl::toData)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderData getOrderForUser(UUID orderId, String userId) {
        // findByIdAndUserId, not findById-then-compare: the owner predicate is in
        // the query, so an order belonging to someone else is never loaded in the
        // first place and there is nothing here to accidentally return.
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderServiceImpl::toData)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * Resolves display names for the cart's products in one batched call.
     * <p>
     * Batched rather than per-line so checkout costs a fixed number of queries
     * however large the basket is. Ids are de-duplicated first; a cart cannot
     * hold two lines for one product today — a unique constraint sees to that —
     * but this method should not be the thing that breaks if that ever changes.
     */
    private Map<Long, String> productNamesFor(List<CartItemSnapshot> items) {
        List<Long> productIds = items.stream()
                .map(CartItemSnapshot::productId)
                .distinct()
                .toList();
        return inventoryFacade.getProducts(productIds).stream()
                .collect(Collectors.toMap(ProductSnapshot::productId, ProductSnapshot::name));
    }

    /**
     * Reads a resolved name, or refuses to write the order without one.
     * <p>
     * Reaching this failure means inventory reserved stock for a product it
     * cannot then describe — a stock row with no product behind it. That should
     * be impossible, which is exactly why it is worth failing on: the
     * alternatives are to invent a placeholder name or store null, and both
     * write a permanent, wrong answer into order history to paper over a data
     * inconsistency that someone needs to look at. Failing here rolls the whole
     * checkout back, so the reservation is released and nothing is left behind.
     */
    private static String productNameOrFail(Map<Long, String> names, Long productId) {
        String name = names.get(productId);
        if (name == null) {
            throw new IllegalStateException(
                    "Cannot snapshot order line: inventory reserved stock for product "
                            + productId + " but reports no such product.");
        }
        return name;
    }

    /** Converts the entity to the detached view every caller of this service gets. */
    private static OrderData toData(Order order) {
        return new OrderData(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getCreatedAt(),
                order.getItems().stream()
                        .map(item -> new OrderItemData(
                                item.getId(),
                                item.getProductId(),
                                item.getProductName(),
                                item.getQuantity(),
                                item.getUnitPriceCents(),
                                item.getCurrency()))
                        .toList());
    }

    /** Narrows a service-layer line to the published shape carried on the event. */
    private static OrderItemSnapshot toItemSnapshot(OrderItemData item) {
        return new OrderItemSnapshot(
                item.productId(),
                item.productName(),
                item.quantity(),
                item.unitPriceCents(),
                item.currency());
    }
}
