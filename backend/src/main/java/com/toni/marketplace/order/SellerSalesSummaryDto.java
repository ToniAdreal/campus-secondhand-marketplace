package com.toni.marketplace.order;

import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sales totals for one seller — the aggregate mirror of the paginated
 * {@code GET /api/seller/orders} list (backlog #108). Everything here is
 * computed from the order rows on the caller's own listings only.
 *
 * <p>Money semantics: {@code grossCents} sums {@code Order.amountCents} —
 * the price snapshot taken at order creation — over PAID and COMPLETED
 * orders only. REFUNDED orders are excluded (the mock capture was handed
 * back), as are PENDING (no capture yet) and CANCELLED orders. Because the
 * snapshot is used, editing a listing's price after an order was placed
 * never rewrites the historical gross.
 *
 * <p>{@code currentMonth} repeats the headline numbers (total orders,
 * completed orders, gross) for the current calendar month only. The month
 * window is {@code [month start, next month start)} over
 * {@code Order.createdAt}, computed from the server clock in the
 * server-local time zone — an honest scope note: this is a single-JVM demo
 * deployment, so "this month" means the host's local calendar month, not
 * the seller's own time zone.
 */
public record SellerSalesSummaryDto(
    long totalOrders,
    long pendingCount,
    long paidCount,
    long completedCount,
    long cancelledCount,
    long refundedCount,
    long grossCents,
    CurrentMonth currentMonth) {

  /** The current-calendar-month slice of the same totals. */
  public record CurrentMonth(
      int year,
      int month,
      long totalOrders,
      long completedCount,
      long grossCents) {}

  /**
   * Builds the summary from the repository's per-status aggregates. A
   * status with no rows is simply absent from the aggregate list and
   * counts as zero — a seller with no orders at all gets an all-zero
   * summary, never an error (mirroring the seller list's empty-page
   * decision in backlog #42).
   */
  public static SellerSalesSummaryDto from(
      List<OrderRepository.StatusAggregate> allTime,
      List<OrderRepository.StatusAggregate> monthRows,
      YearMonth month) {
    Map<OrderStatus, OrderRepository.StatusAggregate> all = byStatus(allTime);
    Map<OrderStatus, OrderRepository.StatusAggregate> inMonth = byStatus(monthRows);
    long gross = grossOf(all);
    return new SellerSalesSummaryDto(
        totalOf(all),
        countOf(all, OrderStatus.PENDING),
        countOf(all, OrderStatus.PAID),
        countOf(all, OrderStatus.COMPLETED),
        countOf(all, OrderStatus.CANCELLED),
        countOf(all, OrderStatus.REFUNDED),
        gross,
        new CurrentMonth(
            month.getYear(),
            month.getMonthValue(),
            totalOf(inMonth),
            countOf(inMonth, OrderStatus.COMPLETED),
            grossOf(inMonth)));
  }

  private static Map<OrderStatus, OrderRepository.StatusAggregate> byStatus(
      List<OrderRepository.StatusAggregate> rows) {
    Map<OrderStatus, OrderRepository.StatusAggregate> map =
        new EnumMap<>(OrderStatus.class);
    for (OrderRepository.StatusAggregate row : rows) {
      map.put(row.getStatus(), row);
    }
    return map;
  }

  private static long totalOf(Map<OrderStatus, OrderRepository.StatusAggregate> rows) {
    return rows.values().stream().mapToLong(OrderRepository.StatusAggregate::getOrderCount).sum();
  }

  private static long countOf(
      Map<OrderStatus, OrderRepository.StatusAggregate> rows, OrderStatus status) {
    OrderRepository.StatusAggregate row = rows.get(status);
    return row == null ? 0L : row.getOrderCount();
  }

  /** Gross is PAID + COMPLETED snapshots only — see the class javadoc. */
  private static long grossOf(Map<OrderStatus, OrderRepository.StatusAggregate> rows) {
    return amountOf(rows, OrderStatus.PAID) + amountOf(rows, OrderStatus.COMPLETED);
  }

  private static long amountOf(
      Map<OrderStatus, OrderRepository.StatusAggregate> rows, OrderStatus status) {
    OrderRepository.StatusAggregate row = rows.get(status);
    return row == null ? 0L : row.getAmountCents();
  }
}
