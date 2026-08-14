package com.devicelk.order.repository;

import com.devicelk.order.domain.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the {@link Order} aggregate.
 * <p>
 * <b>Every read here is scoped by {@code userId}, and that is a security control
 * rather than a convenience.</b> There is deliberately no {@code findById} in use
 * on this repository's own terms: an order is fetched by id <i>and</i> owner
 * together, so an id belonging to another account matches no row and is
 * indistinguishable from one that was never issued. Ownership is not a check
 * layered on after loading — which is the version that gets forgotten in a new
 * endpoint — it is built into the only query that can reach the row.
 * <p>
 * {@code JpaRepository} does still inherit {@code findById}; it is inherited, not
 * offered, and calling it from a request-serving path would reintroduce exactly
 * the gap {@link #findByIdAndUserId(UUID, String)} closes.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Reads one of a user's orders, or nothing.
     * <p>
     * The {@code userId} predicate is what makes {@code GET /api/v1/orders/{id}}
     * answer 404 rather than 403 for somebody else's order: a 403 would confirm
     * the id is real and owned by another account, turning the endpoint into an
     * oracle for probing order ids. This returns empty for both cases, revealing
     * nothing the caller did not already supply.
     * <p>
     * The entity graph pulls the lines in the same round trip, since every caller
     * of this method renders them.
     *
     * @param id     the order id, as supplied by the client
     * @param userId Keycloak {@code sub} of the caller, from the validated token
     * @return the order, or {@link Optional#empty()} if it does not exist or is
     *         not this user's
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdAndUserId(UUID id, String userId);

    /**
     * Reads a user's order history, newest first.
     * <p>
     * The sort is part of the method rather than left to the caller because it
     * pairs with the {@code (user_id, created_at DESC)} index on the table — an
     * arbitrary caller-chosen sort would quietly turn this into a full read plus
     * an in-memory sort.
     * <p>
     * The entity graph is what keeps this from firing one query per order to
     * fetch lines. Unpaged for now, which is honest for a customer's own order
     * history and would need revisiting for an admin-facing view over all users.
     *
     * @param userId Keycloak {@code sub} of the caller, from the validated token
     * @return the user's orders, newest first; empty if they have never ordered
     */
    @EntityGraph(attributePaths = "items")
    List<Order> findByUserIdOrderByCreatedAtDesc(String userId);
}
