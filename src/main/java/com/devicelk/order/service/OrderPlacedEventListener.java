package com.devicelk.order.service;

import com.devicelk.order.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to a committed checkout. For now, by saying so.
 * <p>
 * <b>A wiring proof, deliberately empty of business logic.</b> What it
 * demonstrates is the delivery machinery — that an event published inside
 * checkout arrives after the transaction commits, and survives a listener
 * failure — which is the part that has to be right before anything valuable is
 * hung off it. Payment, confirmation emails and fulfilment are separate concerns
 * that will live in their own modules; building them behind this log line would
 * mean building them before there is anything to verify them against.
 * <p>
 * <b>What {@code @ApplicationModuleListener} buys, in three parts.</b> It is
 * {@code @TransactionalEventListener} with an {@code AFTER_COMMIT} phase, so this
 * never runs for a checkout that rolled back — no confirmation for an order that
 * does not exist. It is {@code @Async}, so a slow or failing listener cannot
 * extend or break the customer's checkout; the caller has already been given
 * their order. And Modulith writes the publication to the
 * {@code event_publication} table in the checkout's own transaction, marking it
 * complete only once this method returns normally — so if this throws, or the
 * process dies mid-handler, the row stays incomplete and is republished on
 * restart rather than the event being silently lost.
 * <p>
 * The consequence of that last part is worth stating: <b>delivery is at-least-once</b>.
 * A handler that does real work must be idempotent, because a crash between
 * doing the work and marking the publication complete will hand it the same
 * event again. Logging is trivially idempotent; charging a card is not.
 * <p>
 * Sits in the order module for now because it is the order module proving its own
 * plumbing. A real notification handler belongs in a notification module, which
 * would subscribe to the same published {@link OrderPlacedEvent} across the
 * boundary — the event type is already in order's published package precisely so
 * that move needs no change here.
 */
@Component
class OrderPlacedEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedEventListener.class);

    @ApplicationModuleListener
    void on(OrderPlacedEvent event) {
        log.info("Order {} placed for user {}, total {} {}",
                event.orderId(), event.userId(), event.totalCents(), event.currency());
    }
}
