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

  /**
   * The buyer's full order history for the account data export
   * (backlog #172), newest first ({@code createdAt} desc, {@code id}
   * desc tie-break) — the export's orders-as-buyer section.
   */
  List<Order> findByBuyerIdOrderByCreatedAtDescIdDesc(Long buyerId);

  /**
   * Every order placed on one seller's listings for the account data
   * export (backlog #172), newest first — the export's
   * orders-as-seller section. The item is fetch-joined because every
   * row's DTO mapping reads the item id, so the export never degrades
   * into one lazy load per row (the #159 export-rows pattern).
   */
  @Query("select o from Order o join fetch o.item "
      + "where o.item.sellerId = :sellerId "
      + "order by o.createdAt desc, o.id desc")
  List<Order> findExportRowsBySellerId(@Param("sellerId") Long sellerId);

  /** Count of orders on one seller's listings in one status — the public profile's completed-sales count (#174). */
  long countByItem_SellerIdAndStatus(Long sellerId, OrderStatus status);

  /** Paginated variant of {@code findByBuyerId} for the buyer's order list. */
  Page<Order> findByBuyerId(Long buyerId, Pageable pageable);

  /**
   * Status-filtered variant of the buyer's order list (backlog #123).
   * The status is bound to the {@link OrderStatus} enum at the controller,
   * so only allowlisted values ever reach this query.
   */
  Page<Order> findByBuyerIdAndStatus(Long buyerId, OrderStatus status, Pageable pageable);

  /**
   * Paginated orders placed on one seller's listings — the seller side of
   * the marketplace read model. Spring Data derives the join on
   * {@code order.item.sellerId}; the page sort is applied by the caller
   * (newest first by default).
   */
  Page<Order> findByItem_SellerId(Long sellerId, Pageable pageable);

  /**
   * Status-filtered variant of the seller's order list (backlog #123) —
   * same scoping as {@link #findByItem_SellerId}, plus the status predicate.
   */
  Page<Order> findByItem_SellerIdAndStatus(Long sellerId, OrderStatus status,
                                           Pageable pageable);

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
   * Per-status aggregates over every order in the marketplace (backlog
   * #134) — the platform-wide counterpart of
   * {@link #summarizeBySellerId}. Statuses with no orders produce no row.
   */
  @Query("select o.status as status, count(o) as orderCount, "
      + "coalesce(sum(o.amountCents), 0) as amountCents from Order o "
      + "group by o.status")
  List<StatusAggregate> summarizeAll();

  /**
   * Captured total for the ADMIN settlement summary (backlog #134): the
   * sum of {@code amountCents} over every order carrying a capture id.
   * A REFUNDED order was still captured, so it counts here too; a PENDING
   * order carries no capture id and contributes nothing. Keyed on the
   * persisted capture id (backlog #114) so the figure survives restarts.
   */
  @Query("select coalesce(sum(o.amountCents), 0) from Order o where o.captureId is not null")
  long sumCapturedCents();

  /**
   * Refunded total for the ADMIN settlement summary (backlog #134): the
   * sum of {@code amountCents} over REFUNDED orders.
   */
  @Query("select coalesce(sum(o.amountCents), 0) from Order o where o.status = :status")
  long sumAmountCentsByStatus(@Param("status") OrderStatus status);

  /**
   * Total refunded money for the ADMIN settlement summary (backlog
   * #210): the sum of the cumulative {@code refundedAmountCents} over
   * every order. This replaces the status-based sum — a PAID order with
   * a partial refund has refunded money but no REFUNDED status, and a
   * status-only total silently dropped it from the reconciliation.
   */
  @Query("select coalesce(sum(o.refundedAmountCents), 0) from Order o")
  long sumRefundedAmountCents();

  /** Windowed variant of {@link #sumRefundedAmountCents} (backlog #210, bounds as in #155). */
  @Query("select coalesce(sum(o.refundedAmountCents), 0) from Order o "
      + "where (:createdFrom is null or o.createdAt >= :createdFrom) "
      + "and (:createdTo is null or o.createdAt < :createdTo)")
  long sumRefundedAmountCentsBetween(@Param("createdFrom") Instant createdFrom,
                                     @Param("createdTo") Instant createdTo);

  /**
   * Windowed variant of {@link #summarizeAll} (backlog #155): same
   * per-status aggregates, restricted to orders created in
   * {@code [createdFrom, createdTo)} over {@code Order.createdAt} — the
   * exact bounds of the seller window ({@link #summarizeBySellerIdBetween}).
   * Either bound may be null, meaning that side is open.
   */
  @Query("select o.status as status, count(o) as orderCount, "
      + "coalesce(sum(o.amountCents), 0) as amountCents from Order o "
      + "where (:createdFrom is null or o.createdAt >= :createdFrom) "
      + "and (:createdTo is null or o.createdAt < :createdTo) "
      + "group by o.status")
  List<StatusAggregate> summarizeAllBetween(@Param("createdFrom") Instant createdFrom,
                                            @Param("createdTo") Instant createdTo);

  /**
   * Orders created in {@code [from, to)} carrying a persisted capture
   * id — the ADMIN dashboard's windowed capture count (#196). A count,
   * not a sum: the dashboard reports activity volume; money totals
   * stay with the settlement summary (#134/#155).
   */
  @Query("select count(o) from Order o where o.captureId is not null "
      + "and o.createdAt >= :from and o.createdAt < :to")
  long countCapturesBetween(@Param("from") Instant from, @Param("to") Instant to);

  /** Refund mirror of {@link #countCapturesBetween} (#196), keyed on the persisted refund id. */
  @Query("select count(o) from Order o where o.refundId is not null "
      + "and o.createdAt >= :from and o.createdAt < :to")
  long countRefundsBetween(@Param("from") Instant from, @Param("to") Instant to);

  /** Windowed variant of {@link #sumCapturedCents} (backlog #155). */
  @Query("select coalesce(sum(o.amountCents), 0) from Order o "
      + "where o.captureId is not null "
      + "and (:createdFrom is null or o.createdAt >= :createdFrom) "
      + "and (:createdTo is null or o.createdAt < :createdTo)")
  long sumCapturedCentsBetween(@Param("createdFrom") Instant createdFrom,
                               @Param("createdTo") Instant createdTo);

  /**
   * Rows for the seller sales CSV export (backlog #159): the caller's own
   * orders in the given statuses (the export passes PAID/COMPLETED only),
   * optionally windowed to {@code [createdFrom, createdTo)} over
   * {@code createdAt} — the #155 bounds; either bound may be null (open
   * side). Newest first ({@code createdAt} desc, {@code id} desc
   * tie-break), mirroring the seller order list. The item is fetch-joined
   * because every row renders the listing title, so the export never
   * degrades into one lazy load per row.
   */
  @Query("select o from Order o join fetch o.item "
      + "where o.item.sellerId = :sellerId and o.status in :statuses "
      + "and (:createdFrom is null or o.createdAt >= :createdFrom) "
      + "and (:createdTo is null or o.createdAt < :createdTo) "
      + "order by o.createdAt desc, o.id desc")
  List<Order> findSellerSalesExportRows(@Param("sellerId") Long sellerId,
                                        @Param("statuses") Collection<OrderStatus> statuses,
                                        @Param("createdFrom") Instant createdFrom,
                                        @Param("createdTo") Instant createdTo);

  /** Windowed variant of {@link #sumAmountCentsByStatus} (backlog #155). */
  @Query("select coalesce(sum(o.amountCents), 0) from Order o "
      + "where o.status = :status "
      + "and (:createdFrom is null or o.createdAt >= :createdFrom) "
      + "and (:createdTo is null or o.createdAt < :createdTo)")
  long sumAmountCentsByStatusBetween(@Param("status") OrderStatus status,
                                     @Param("createdFrom") Instant createdFrom,
                                     @Param("createdTo") Instant createdTo);

  /**
   * Application-level counterpart of the {@code uq_order_active_item}
   * constraint: true when the item already sits in an order with one of the
   * given statuses. Checked before creating an order for a friendly 409; the
   * database constraint remains the final arbiter under races.
   */
  /** Webhook reconciliation lookup by the persisted PSP capture id (#114/#140). */
  java.util.Optional<Order> findByCaptureId(String captureId);

  /** Webhook reconciliation lookup by the persisted PSP refund id (#114/#140). */
  java.util.Optional<Order> findByRefundId(String refundId);

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
