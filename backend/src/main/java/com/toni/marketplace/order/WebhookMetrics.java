package com.toni.marketplace.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Mock-PSP webhook counters (backlog #140), exported on
 * {@code /actuator/prometheus} alongside {@link OrderMetrics} (#93).
 *
 * <ul>
 *   <li>{@code payments.webhook.matched} — a first-seen delivery whose PSP
 *       reference named an order already in the matching state.</li>
 *   <li>{@code payments.webhook.mismatched} — a first-seen delivery that
 *       did not reconcile (unknown reference, wrong state, unknown event
 *       type). This is the "counted" half of the backlog's
 *       logged-and-counted mismatch rule: a rising value means the
 *       synchronous path and the PSP disagree and an operator should look.</li>
 *   <li>{@code payments.webhook.replayed} — a delivery whose event id was
 *       already recorded. Replays are answered 200 and count ONLY here —
 *       never as a second matched/mismatched — so the matched+mismatched
 *       total always equals the number of distinct events applied.</li>
 * </ul>
 *
 * <p>Signature failures (401) count nowhere here: they never reach the
 * reconciliation path, and counting unauthenticated garbage would let
 * anyone inflate the operational signal. No tags carry event ids or PSP
 * references — the same cardinality/PII rule as {@link OrderMetrics}.
 */
@Component
public class WebhookMetrics {

  private final Counter matched;
  private final Counter mismatched;
  private final Counter replayed;

  public WebhookMetrics(MeterRegistry registry) {
    MeterRegistry reg = registry != null ? registry : new SimpleMeterRegistry();
    this.matched = Counter.builder("payments.webhook.matched")
        .description("Mock-PSP webhook deliveries reconciled against an order in the matching state")
        .register(reg);
    this.mismatched = Counter.builder("payments.webhook.mismatched")
        .description("Mock-PSP webhook deliveries that did not reconcile (unknown reference or state)")
        .register(reg);
    this.replayed = Counter.builder("payments.webhook.replayed")
        .description("Mock-PSP webhook deliveries replayed with an already-seen event id")
        .register(reg);
  }

  /** Counters on a throwaway registry — for tests constructed outside Spring. */
  public static WebhookMetrics noop() {
    return new WebhookMetrics(new SimpleMeterRegistry());
  }

  public void matched() { matched.increment(); }
  public void mismatched() { mismatched.increment(); }
  public void replayed() { replayed.increment(); }
}
