package com.toni.marketplace.dispute;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Dispute-domain Micrometer gauge (backlog #432), exported on
 * {@code /actuator/prometheus} alongside the order counters in
 * {@link com.toni.marketplace.order.OrderMetrics}.
 *
 * <ul>
 *   <li>{@code disputes.oldest_open_age_hours} — age in hours of the
 *       oldest still-OPEN dispute, zero when none is open. The value
 *       is read live from {@link DisputeService#oldestOpenDisputeAgeHours}
 *       on every scrape, so it tracks opens and resolutions with no
 *       write-side bookkeeping: opening a dispute starts its age at
 *       its {@code created_at}, resolving the oldest hands the gauge
 *       to the next-oldest open dispute, and resolving the last one
 *       drops it to zero. A gauge, not a counter: the value goes down
 *       as well as up, and the Prometheus series carries no
 *       {@code _total} suffix. No tags — the age carries no order id,
 *       user id, or reason, for the same cardinality/PII reason the
 *       counters carry none. The read is one indexed lookup
 *       (V36 {@code idx_dispute_status_created}) per scrape.</li>
 * </ul>
 *
 * <p>Why a gauge at all: the only dispute alert before this one was
 * {@code OrderDisputeRateHigh}, a rate rule over
 * {@code orders.disputed} — a single dispute ignored for weeks never
 * moves a rate, so it never paged. The gauge is the only surface
 * that sees one stale dispute.
 */
@Component
public class DisputeMetrics {

  public DisputeMetrics(MeterRegistry registry, DisputeService disputes) {
    MeterRegistry reg = registry != null ? registry : new SimpleMeterRegistry();
    Gauge.builder("disputes.oldest_open_age_hours", disputes,
            DisputeService::oldestOpenDisputeAgeHours)
        .description("Age in hours of the oldest still-OPEN dispute (0 when none is open)")
        .register(reg);
  }
}
