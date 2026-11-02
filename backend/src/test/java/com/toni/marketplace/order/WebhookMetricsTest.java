package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Backlog #300 — direct unit tests for {@link WebhookMetrics}. The sibling
 * metrics classes already have coverage through Spring-context suites
 * ({@code AuthSecurityMetricsTest} for AuthMetrics,
 * {@code OrderPaymentMetricsTest} for OrderMetrics), but WebhookMetrics had
 * no test of its own: a counter wired to the wrong name would only surface
 * as a silent gap on the Prometheus scrape. These tests run on a throwaway
 * {@link SimpleMeterRegistry} — the same shape as
 * {@link WebhookMetrics#noop()} — with no Spring context and no database,
 * so each increment is pinned to exactly one counter.
 */
class WebhookMetricsTest {

  private static final String MATCHED = "payments.webhook.matched";
  private static final String MISMATCHED = "payments.webhook.mismatched";
  private static final String REPLAYED = "payments.webhook.replayed";

  private double count(SimpleMeterRegistry registry, String name) {
    Counter counter = registry.find(name).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private void assertCounts(SimpleMeterRegistry registry,
      double matched, double mismatched, double replayed) {
    assertThat(count(registry, MATCHED)).isEqualTo(matched);
    assertThat(count(registry, MISMATCHED)).isEqualTo(mismatched);
    assertThat(count(registry, REPLAYED)).isEqualTo(replayed);
  }

  @Test
  void freshRegistryStartsAtZero() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new WebhookMetrics(registry);

    // Constructing the counters registers them; none has been incremented.
    assertCounts(registry, 0.0, 0.0, 0.0);
  }

  @Test
  void matchedIncrementsOnlyMatched() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WebhookMetrics metrics = new WebhookMetrics(registry);

    metrics.matched();
    assertCounts(registry, 1.0, 0.0, 0.0);

    metrics.matched();
    assertCounts(registry, 2.0, 0.0, 0.0);
  }

  @Test
  void mismatchedIncrementsOnlyMismatched() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WebhookMetrics metrics = new WebhookMetrics(registry);

    metrics.mismatched();
    assertCounts(registry, 0.0, 1.0, 0.0);

    metrics.mismatched();
    assertCounts(registry, 0.0, 2.0, 0.0);
  }

  @Test
  void replayedIncrementsOnlyReplayed() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WebhookMetrics metrics = new WebhookMetrics(registry);

    metrics.replayed();
    assertCounts(registry, 0.0, 0.0, 1.0);

    metrics.replayed();
    assertCounts(registry, 0.0, 0.0, 2.0);
  }

  @Test
  void mixedSequenceCountsEachCounterIndependently() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    WebhookMetrics metrics = new WebhookMetrics(registry);

    // Mirrors the production invariant in WebhookMetrics' javadoc: a replay
    // counts ONLY as replayed, never as a second matched/mismatched, so the
    // matched+mismatched total equals the number of distinct events applied.
    metrics.matched();
    metrics.matched();
    metrics.mismatched();
    metrics.replayed();
    metrics.replayed();
    metrics.replayed();

    assertCounts(registry, 2.0, 1.0, 3.0);
  }

  @Test
  void noopIncrementsWithoutError() {
    // noop() owns a throwaway registry, so its counters cannot be read back
    // from here; this only pins that the factory path itself is usable.
    WebhookMetrics metrics = WebhookMetrics.noop();
    metrics.matched();
    metrics.mismatched();
    metrics.replayed();
  }
}
