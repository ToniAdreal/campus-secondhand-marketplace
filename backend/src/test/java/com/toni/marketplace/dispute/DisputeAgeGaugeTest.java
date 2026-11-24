package com.toni.marketplace.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import com.toni.marketplace.order.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Backlog #432 — the {@code disputes.oldest_open_age_hours} gauge:
 * it tracks the oldest still-OPEN dispute (in hours, fractional) and
 * drops to zero when the last one resolves. Pure unit tests,
 * mirroring {@code OrderLifecycleMetricsTest} (#348): the service is
 * constructed by hand with mocked repositories and a fixed clock, the
 * gauge lives on a throwaway {@link SimpleMeterRegistry}, and every
 * assertion reads the gauge itself — so a gauge that forgets to move,
 * or moves on the wrong dispute, fails here. No Spring context, no
 * database.
 *
 * <p>The resolution itself is covered by {@code OrderDisputeTest}
 * (the ADMIN resolve paths); what is pinned here is the gauge's read
 * of the repository state a resolution leaves behind — the oldest
 * row gone (the next-oldest becomes the gauge's value) or no OPEN
 * row left (zero). The repository is therefore stubbed per test to
 * the state before and after the resolve.
 */
@ExtendWith(MockitoExtension.class)
class DisputeAgeGaugeTest {

  private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

  @Mock
  private OrderDisputeRepository disputes;

  @Mock
  private OrderRepository orders;

  @Mock
  private ItemRepository items;

  @Mock
  private PaymentService payments;

  private MeterRegistry registry;
  private DisputeService disputeService;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    disputeService = new DisputeService(disputes, orders, items, payments,
        new DisputeProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
    new DisputeMetrics(registry, disputeService);
  }

  private double gaugeValue() {
    return registry.get("disputes.oldest_open_age_hours").gauge().value();
  }

  /** An OPEN dispute whose created_at was set by hand (no persist, so no @PrePersist). */
  private OrderDispute openDispute(long orderId, Instant createdAt) {
    OrderDispute dispute = new OrderDispute(orderId, 22L, 11L, "never arrived",
        OrderStatus.PAID);
    ReflectionTestUtils.setField(dispute, "createdAt", createdAt);
    return dispute;
  }

  private void oldestOpenIs(OrderDispute dispute) {
    when(disputes.findFirstByStatusOrderByCreatedAtAsc(DisputeStatus.OPEN))
        .thenReturn(Optional.ofNullable(dispute));
  }

  @Test
  void gaugeTracksOldestOpenDisputeInFractionalHours() {
    OrderDispute oldest = openDispute(33L, NOW.minus(Duration.ofDays(10)));
    OrderDispute newer = openDispute(34L, NOW.minus(Duration.ofDays(2)));
    oldestOpenIs(oldest);

    // The repository returns the oldest row, not the newer one: 10 days, not 2.
    assertThat(disputeService.oldestOpenDisputeAgeHours()).isEqualTo(240.0);
    assertThat(gaugeValue()).isEqualTo(240.0);
    // The newer dispute is not what the gauge reads — swapping the stub
    // to it proves the value follows the repository's oldest row.
    oldestOpenIs(newer);
    assertThat(gaugeValue()).isEqualTo(48.0);
    // Fractional hours are real hours, not truncated days: 90 minutes is 1.5.
    oldestOpenIs(openDispute(35L, NOW.minus(Duration.ofMinutes(90))));
    assertThat(gaugeValue()).isCloseTo(1.5, within(1e-9));
  }

  @Test
  void gaugeDropsToNextOldestWhenOldestResolvesThenToZero() {
    OrderDispute oldest = openDispute(33L, NOW.minus(Duration.ofDays(10)));
    OrderDispute newer = openDispute(34L, NOW.minus(Duration.ofDays(2)));
    oldestOpenIs(oldest);
    assertThat(gaugeValue()).isEqualTo(240.0);

    // The oldest dispute resolves: the repository's oldest OPEN row is
    // now the newer one, and the gauge hands over to it.
    oldestOpenIs(newer);
    assertThat(gaugeValue()).isEqualTo(48.0);

    // The last open dispute resolves: no OPEN row is left, the gauge is zero.
    oldestOpenIs(null);
    assertThat(gaugeValue()).isEqualTo(0.0);
    assertThat(disputeService.oldestOpenDisputeAgeHours()).isEqualTo(0.0);
  }

  @Test
  void gaugeIsZeroWhenNoDisputeWasEverOpened() {
    oldestOpenIs(null);
    assertThat(gaugeValue()).isEqualTo(0.0);
  }
}
