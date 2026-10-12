package com.toni.marketplace.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 *   <li>{@code payments.declined} — the mock PSP declined a capture
 *       (#115, extending #92). This is deliberately not an
 *       {@code orders.*} counter: a decline is not an order transition
 *       (the order stays PENDING), it is a payment-attempt outcome, and
 *       without it a wave of declines would be invisible on
 *       {@code /actuator/prometheus} while {@code orders.paid} simply
 *       flatlines. Counted at exactly the decline point in
 *       {@link OrderService#pay(Long, Long, String)} — never on a 409
 *       race loss, a validation 400, or an idempotent re-pay (which
 *       returns before any capture is attempted).</li>
 * </ul>
 *
 * <p>Latency timers (backlog #153) live here too, so the counters and
 * the timers cannot drift apart:
 * <ul>
 *   <li>{@code orders.create.duration} — timed from the moment a fresh
 *       creation is attempted in {@link OrderService#createOrder} (the
 *       same point the {@code orders.created} counter keys on):
 *       {@code outcome=success} for a fresh creation, {@code
 *       outcome=failure} when the attempt throws (unknown item, item
 *       unavailable, a lost race mapped to 409, …).</li>
 *   <li>{@code orders.pay.duration} — timed from the moment a capture
 *       is attempted in {@link OrderService#pay}: {@code
 *       outcome=success} for a fresh PENDING → PAID transition,
 *       {@code outcome=failure} for a declined capture or a 409 race
 *       loss inside the attempt.</li>
 * </ul>
 * Idempotent replays are treated exactly as the counters treat them —
 * they record NO timer sample: an Idempotency-Key replay returns the
 * original order before any creation is attempted, and a re-pay of an
 * already-PAID order returns before any capture is attempted.
 * Pre-attempt rejections likewise record no sample (pay: unknown
 * order 404, non-buyer 403, terminal-state 422; create: an invalid
 * Idempotency-Key 400) — the timers measure the hot-path work, not the
 * validation rejects in front of it, mirroring how no transition
 * counter moves on those paths either.
 *
 * <p>Counters increment at exactly the transition points in
 * {@link OrderService}, after the deliberate in-transaction flush that
 * surfaces a lost {@code @Version} race as 409 — so a transition that
 * loses a race (or rolls back) is never counted. The event is encoded
 * in the counter name itself — deliberately no tags carrying
 * usernames, order ids, or IPs: tag values would be both a cardinality
 * hazard and a PII leak into the metrics store. The timers carry
 * exactly one tag, {@code outcome} ({@code success} / {@code
 * failure}), for the same reason. Honest scope: these meters exist
 * and are scrapeable; alerting on them does not exist (see the README
 * observability row).
 */
@Component
public class OrderMetrics {

  private final MeterRegistry registry;
  private final Counter created;
  private final Counter paid;
  private final Counter cancelled;
  private final Counter completed;
  private final Counter refunded;
  private final Counter expired;
  private final Counter paymentsDeclined;
  private final Timer createSuccessTimer;
  private final Timer createFailureTimer;
  private final Timer paySuccessTimer;
  private final Timer payFailureTimer;

  public OrderMetrics(MeterRegistry registry) {
    MeterRegistry reg = registry != null ? registry : new SimpleMeterRegistry();
    this.registry = reg;
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
    this.paymentsDeclined = Counter.builder("payments.declined")
        .description("Mock PSP capture declines (order stays PENDING)")
        .register(reg);
    this.createSuccessTimer = Timer.builder("orders.create.duration")
        .tag("outcome", "success")
        .description("Order-creation attempt latency, fresh creations")
        .register(reg);
    this.createFailureTimer = Timer.builder("orders.create.duration")
        .tag("outcome", "failure")
        .description("Order-creation attempt latency, failed attempts")
        .register(reg);
    this.paySuccessTimer = Timer.builder("orders.pay.duration")
        .tag("outcome", "success")
        .description("Order-pay capture attempt latency, fresh payments")
        .register(reg);
    this.payFailureTimer = Timer.builder("orders.pay.duration")
        .tag("outcome", "failure")
        .description("Order-pay capture attempt latency, declined or raced attempts")
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

  public void paymentsDeclined() {
    paymentsDeclined.increment();
  }

  /** Starts a create timer sample; stop it exactly once via {@link #stopCreateTimer}. */
  public Timer.Sample startCreateTimer() {
    return Timer.start(registry);
  }

  /** Stops a create sample on the success or failure timer, per {@code success}. */
  public void stopCreateTimer(Timer.Sample sample, boolean success) {
    if (sample != null) {
      sample.stop(success ? createSuccessTimer : createFailureTimer);
    }
  }

  /** Starts a pay timer sample; stop it exactly once via {@link #stopPayTimer}. */
  public Timer.Sample startPayTimer() {
    return Timer.start(registry);
  }

  /** Stops a pay sample on the success or failure timer, per {@code success}. */
  public void stopPayTimer(Timer.Sample sample, boolean success) {
    if (sample != null) {
      sample.stop(success ? paySuccessTimer : payFailureTimer);
    }
  }
}
