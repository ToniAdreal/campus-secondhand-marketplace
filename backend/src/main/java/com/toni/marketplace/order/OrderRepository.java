package com.toni.marketplace.order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

  List<Order> findByBuyerId(Long buyerId);

  /** Paginated variant of {@code findByBuyerId} for the buyer's order list. */
  Page<Order> findByBuyerId(Long buyerId, Pageable pageable);

  /**
   * Paginated orders placed on one seller's listings — the seller side of
   * the marketplace read model. Spring Data derives the join on
   * {@code order.item.sellerId}; the page sort is applied by the caller
   * (newest first by default).
   */
  Page<Order> findByItem_SellerId(Long sellerId, Pageable pageable);

  /**
   * One row of a per-status sales aggregate: how many orders sit in the
   * status and the sum of their {@code amountCents} price snapshots.
   * Projection for the seller sales summary (backlog #108) — the summary
   * decides which statuses count toward gross, the query stays neutral.
   */
  interface StatusAggregate {
    OrderStatus getStatus();

    long getOrderCount();

    long getAmountCents();
  }

  /**
   * Per-status aggregates over every order placed on one seller's
   * listings (backlog #108). Statuses with no orders produce no row.
   */
  @Query("select o.status as status, count(o) as orderCount, "
      + "coalesce(sum(o.amountCents), 0) as amountCents from Order o "
      + "where o.item.sellerId = :sellerId group by o.status")
  List<StatusAggregate> summarizeBySellerId(@Param("sellerId") Long sellerId);

  /**
   * Same aggregate restricted to orders created in
   * {@code [createdFrom, createdTo)} — the current-month window of the
   * seller sales summary (backlog #108).
   */
  @Query("select o.status as status, count(o) as orderCount, "
      + "coalesce(sum(o.amountCents), 0) as amountCents from Order o "
      + "where o.item.sellerId = :sellerId "
      + "and o.createdAt >= :createdFrom and o.createdAt < :createdTo "
      + "group by o.status")
  List<StatusAggregate> summarizeBySellerIdBetween(@Param("sellerId") Long sellerId,
                                                   @Param("createdFrom") Instant createdFrom,
                                                   @Param("createdTo") Instant createdTo);

  /**
   * Application-level counterpart of the {@code uq_order_active_item}
   * constraint: true when the item already sits in an order with one of the
   * given statuses. Checked before creating an order for a friendly 409; the
   * database constraint remains the final arbiter under races.
   */
  boolean existsByItem_IdAndStatusIn(Long itemId, Collection<OrderStatus> statuses);

  /**
   * Worklist for the stale-PENDING-order expiry job: ids of orders in the
   * given status created before the cutoff. Projects only the id so the
   * batch scan stays narrow; the per-row expiry re-checks the status under
   * its own transaction, so rows that transitioned between the scan and the
   * row transaction are skipped, never wrongly cancelled.
   */
  @Query("select o.id from Order o where o.status = :status and o.createdAt < :createdBefore")
  List<Long> findIdsByStatusAndCreatedAtBefore(@Param("status") OrderStatus status,
                                              @Param("createdBefore") Instant createdBefore,
                                              Pageable pageable);
}
