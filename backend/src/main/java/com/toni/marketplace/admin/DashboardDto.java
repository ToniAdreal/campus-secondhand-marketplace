package com.toni.marketplace.admin;

import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * ADMIN operations dashboard (backlog #196): one aggregate answer to
 * "is the marketplace healthy today", assembled from counts only —
 * no row data and no user identifiers ever appear here (a key-set
 * test pins the exact field set).
 *
 * <p>Window semantics: {@code window} / {@code windowStart} echo the
 * resolved look-back window ({@code [windowStart, now)}). The fields
 * fall into two honestly distinct groups:
 * <ul>
 *   <li><b>Windowed activity</b> — counted over rows created inside
 *       the window: {@code usersRegistered}, {@code listingsCreated},
 *       the per-status order counts (an order counts under the status
 *       it is in <em>now</em>, grouped by its creation time — orders
 *       carry their creation instant, and this mirrors the #155
 *       settlement window), {@code captures} / {@code refunds}
 *       (orders created in the window carrying a persisted capture /
 *       refund id, backlog #114) and the webhook outcome counts
 *       (deliveries received in the window).</li>
 *   <li><b>Current snapshots</b> — the state right now, unaffected by
 *       the window: {@code listingsAvailableNow}, {@code openReports}
 *       and {@code openDisputes}. A point-in-time backlog cannot be
 *       windowed honestly, so it is not.</li>
 * </ul>
 * {@code listingsExpiredInWindow} counts listings whose lifetime ended
 * inside the window: {@code expiresAt} in the window and the listing
 * either already flipped to the stored {@code EXPIRED} status or still
 * stored {@code AVAILABLE} past its deadline (the nightly flip of
 * backlog #177 lags the deadline by at most one cadence, and the
 * public reads already treat such a row as expired — the count uses
 * the deadline, the durable fact, not the bookkeeping flip time).
 *
 * <p>Deliberately absent: a declines count. The spec asked for
 * captures vs declines, but a declined capture leaves no durable row
 * anywhere — {@code PaymentService} stores nothing on a decline
 * (backlog #92) and the order simply stays PENDING; the only decline
 * count in the system is the JVM-local Micrometer counter
 * {@code payments.declined} (#115), which resets on restart and
 * cannot be windowed. Per this backlog's own rule — a metric with no
 * source is cut, never estimated — declines are cut here and remain
 * visible on {@code /actuator/prometheus} instead.
 */
public record DashboardDto(
    String window,
    Instant windowStart,
    long usersRegistered,
    long listingsCreated,
    long listingsAvailableNow,
    long listingsExpiredInWindow,
    long ordersTotalInWindow,
    long ordersPending,
    long ordersPaid,
    long ordersCompleted,
    long ordersCancelled,
    long ordersRefunded,
    long ordersDisputed,
    long captures,
    long refunds,
    long openReports,
    long openDisputes,
    long webhooksMatched,
    long webhooksMismatched) {

  /**
   * Assembles the DTO from the per-status order aggregate rows of the
   * window plus the scalar counts. A status with no rows in the window
   * is absent from the aggregate list and counts as zero (mirroring
   * {@code SettlementSummaryDto}); {@code ordersTotalInWindow} is the
   * sum over the aggregate, so it always equals the per-status sum.
   */
  public static DashboardDto from(
      DashboardWindow window,
      Instant windowStart,
      long usersRegistered,
      long listingsCreated,
      long listingsAvailableNow,
      long listingsExpiredInWindow,
      List<OrderRepository.StatusAggregate> orderRows,
      long captures,
      long refunds,
      long openReports,
      long openDisputes,
      long webhooksMatched,
      long webhooksMismatched) {
    Map<OrderStatus, OrderRepository.StatusAggregate> byStatus =
        new EnumMap<>(OrderStatus.class);
    for (OrderRepository.StatusAggregate row : orderRows) {
      byStatus.put(row.getStatus(), row);
    }
    long total = byStatus.values().stream()
        .mapToLong(OrderRepository.StatusAggregate::getOrderCount).sum();
    return new DashboardDto(
        window.wireValue(),
        windowStart,
        usersRegistered,
        listingsCreated,
        listingsAvailableNow,
        listingsExpiredInWindow,
        total,
        countOf(byStatus, OrderStatus.PENDING),
        countOf(byStatus, OrderStatus.PAID),
        countOf(byStatus, OrderStatus.COMPLETED),
        countOf(byStatus, OrderStatus.CANCELLED),
        countOf(byStatus, OrderStatus.REFUNDED),
        countOf(byStatus, OrderStatus.DISPUTED),
        captures,
        refunds,
        openReports,
        openDisputes,
        webhooksMatched,
        webhooksMismatched);
  }

  private static long countOf(
      Map<OrderStatus, OrderRepository.StatusAggregate> rows, OrderStatus status) {
    OrderRepository.StatusAggregate row = rows.get(status);
    return row == null ? 0L : row.getOrderCount();
  }
}
