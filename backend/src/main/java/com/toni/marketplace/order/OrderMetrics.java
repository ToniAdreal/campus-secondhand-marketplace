package com.toni.marketplace.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Order/payment-domain Micrometer counters (backlog #93), mirroring
 * {@code AuthMetrics} (#79) and exported on {@code /actuator/prometheus}
 * (#57) alongside the JVM and auth metrics.
 *
 * <ul>
 *   <li>{@code orders.created} — a fresh order was created. An
 *       Idempotency-Key replay returning the original order does not
 *       count: no new transition happened.</li>
 *   <li>{@code orders.paid} — PENDING → PAID via the mock capture.
 *       A re-pay of an already-PAID order (idempotent no-op) does not
 *       count, and neither does a declined capture (#92).</li>
 *   <li>{@code orders.cancelled} — PENDING → CANCELLED by the buyer.
 *       Re-cancelling an already-CANCELLED order does not count.</li>
 *   <li>{@code orders.completed} — PAID → COMPLETED by the seller.
 *       Re-completing does not count.</li>
 *   <li>{@code orders.refunded} — PAID → REFUNDED by the seller.
 *       Re-refunding does not count (and issues no second refund).</li>
 *   <li>{@code orders.expired} — a stale PENDING order was cancelled by
 *       the expiry job ({@link PendingOrderExpiryService} via
 *       {@link OrderService#expireStaleOrder}). Counted here, not as
 *       {@code orders.cancelled}: expiry is a system transition, not a
 *       buyer action, and the two must stay distinguishable.</li>
 * </ul>
 *
 * <p>Counters increment at exactly the transition points in
 * {@link OrderService}, after the deliberate in-transaction flush that
 * surfaces a lost {@code @Version} race as 409 — so a transition that
 * loses a race (or rolls back) is never counted. The event is encoded
 * in the counter name itself — deliberately no tags carrying
 * usernames, order ids, or IPs: tag values would be both a cardinality
 * hazard and a PII leak into the metrics store. Honest scope: these
 * counters exist and are scrapeable; alerting on them does not exist
 * (see the README observability row).
 */
@Component
public class OrderMetrics {

  private final Counter created;
  private final Counter paid;
  private final Counter cancelled;
  private final Counter completed;
  private final Counter refunded;
  private final Counter expired;

  public OrderMetrics(MeterRegistry registry) {
    MeterRegistry reg = registry != null ? registry : new SimpleMeterRegistry();
    this.created = Counter.builder("orders.created")
        .description("Fresh orders created (Idempotency-Key replays excluded)")
        .register(reg);
    this.paid = Counter.builder("orders.paid")
        .description("Orders transitioned PENDING to PAID via mock capture")
        .register(reg);
    this.cancelled = Counter.builder("orders.cancelled")
        .description("PENDING orders cancelled by their buyer")
        .register(reg);
    this.completed = Counter.builder("orders.completed")
        .description("PAID orders completed by their seller")
        .register(reg);
    this.refunded = Counter.builder("orders.refunded")
        .description("PAID orders refunded by their seller")
        .register(reg);
    this.expired = Counter.builder("orders.expired")
        .description("Stale PENDING orders expired by the scheduled job")
        .register(reg);
  }

  /**
   * Counters on a throwaway registry — for code constructed outside
   * Spring (direct unit tests) that does not assert on metrics. The
   * increments still happen; they are simply never scraped.
   */
  public static OrderMetrics noop() {
    return new OrderMetrics(new SimpleMeterRegistry());
  }

  public void created() {
    created.increment();
  }

  public void paid() {
    paid.increment();
  }

  public void cancelled() {
    cancelled.increment();
  }

  public void completed() {
    completed.increment();
  }

  public void refunded() {
    refunded.increment();
  }

  public void expired() {
    expired.increment();
  }
}
