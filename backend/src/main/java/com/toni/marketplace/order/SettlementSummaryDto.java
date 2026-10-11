package com.toni.marketplace.order;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Mock-PSP settlement summary for ADMINs (backlog #134) — the platform-wide
 * counterpart of the per-seller {@link SellerSalesSummaryDto} (#108).
 *
 * <p>Everything here is reconciled from the orders table, the database of
 * record, never from {@code PaymentService}'s JVM in-memory counters
 * ({@code capturesIssued} / {@code refundsIssued} are lost on restart and
 * count invocations, not money) and never from {@code OrderMetrics} (which
 * counts transitions, not money). That is exactly why backlog #114 persisted
 * the PSP capture/refund ids on the order rows: the figures below survive
 * restarts because they are derived from those rows.
 *
 * <p>Money semantics:
 * <ul>
 *   <li>{@code capturedCents} — sum of {@code amountCents} over every order
 *       carrying a capture id. A REFUNDED order was still captured, so it
 *       counts here as well as in {@code refundedCents}.</li>
 *   <li>{@code refundedCents} — sum of {@code amountCents} over REFUNDED
 *       orders.</li>
 *   <li>{@code netCents} — {@code capturedCents - refundedCents}.</li>
 *   <li>A PENDING (or CANCELLED) order carries no capture id and
 *       contributes nothing to any money total — only to the per-status
 *       counts.</li>
 * </ul>
 *
 * <p>Honest scope: this is mock money. The figures are derived totals over
 * the demo orders table only — no PSP settlement file is imported, no real
 * processor is reconciled, and no real money has moved.
 */
public record SettlementSummaryDto(
    long capturedCents,
    long refundedCents,
    long netCents,
    long totalOrders,
    long pendingCount,
    long paidCount,
    long completedCount,
    long cancelledCount,
    long refundedCount) {

  /**
   * Builds the summary from the repository aggregates. A status with no
   * rows is simply absent from the aggregate list and counts as zero — an
   * empty database yields an all-zero summary, never an error (mirroring
   * the seller summary's decision in backlog #108).
   */
  public static SettlementSummaryDto from(
      List<OrderRepository.StatusAggregate> rows, long capturedCents, long refundedCents) {
    Map<OrderStatus, OrderRepository.StatusAggregate> byStatus = new EnumMap<>(OrderStatus.class);
    for (OrderRepository.StatusAggregate row : rows) {
      byStatus.put(row.getStatus(), row);
    }
    long total = byStatus.values().stream()
        .mapToLong(OrderRepository.StatusAggregate::getOrderCount).sum();
    return new SettlementSummaryDto(
        capturedCents,
        refundedCents,
        capturedCents - refundedCents,
        total,
        countOf(byStatus, OrderStatus.PENDING),
        countOf(byStatus, OrderStatus.PAID),
        countOf(byStatus, OrderStatus.COMPLETED),
        countOf(byStatus, OrderStatus.CANCELLED),
        countOf(byStatus, OrderStatus.REFUNDED));
  }

  private static long countOf(
      Map<OrderStatus, OrderRepository.StatusAggregate> rows, OrderStatus status) {
    OrderRepository.StatusAggregate row = rows.get(status);
    return row == null ? 0L : row.getOrderCount();
  }
}
