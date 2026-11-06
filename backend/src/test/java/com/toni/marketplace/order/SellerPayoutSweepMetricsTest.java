package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toni.marketplace.job.JobLockGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Pure unit tests for the payout-sweep counters (backlog #341). No Spring
 * context: the repositories and transitions are Mockito mocks, the
 * counters live on a throwaway {@link SimpleMeterRegistry}, and every
 * assertion reads the counter itself — not the sweep's returned counts
 * — so a counter that forgets to move, or moves on the wrong outcome,
 * fails here. The existing {@code SellerPayoutTest} drives the same
 * sweep through the real repositories, but its context-boot cost would
 * make every counter assertion pay a full boot.
 *
 * <p>Discipline pinned here, mirroring {@link OrderMetrics}: one
 * increment per row outcome, and the counters always agree with the
 * sweep's own {@code PayoutSweepCounts} — a sweep that silently pays
 * nothing, or holds everything for disabled sellers, must be visible
 * on {@code /actuator/prometheus}, not only in the run log.
 */
class SellerPayoutSweepMetricsTest {

  private static final Instant NOW = Instant.parse("2026-11-06T11:00:00Z");

  private SellerPayoutRepository payouts;
  private SellerPayoutTransitions transitions;
  private MeterRegistry registry;
  private OrderMetrics metrics;
  private SellerPayoutService service;

  @BeforeEach
  void setUp() {
    payouts = mock(SellerPayoutRepository.class);
    transitions = mock(SellerPayoutTransitions.class);
    registry = new SimpleMeterRegistry();
    metrics = new OrderMetrics(registry);
    service = new SellerPayoutService(
        payouts,
        mock(SellerPayoutAdjustmentRepository.class),
        transitions,
        Clock.fixed(NOW, ZoneOffset.UTC),
        metrics);
  }

  private double count(String name) {
    return registry.get(name).counter().count();
  }

  @Test
  void promotedRowMovesOnlyThePromotedCounter() {
    when(payouts.findIdsByStatus(PayoutStatus.PENDING)).thenReturn(List.of(1L));
    when(payouts.findIdsByStatus(PayoutStatus.PAYABLE)).thenReturn(List.of());
    when(transitions.promoteToPayable(eq(1L), any())).thenReturn(true);

    SellerPayoutService.PayoutSweepCounts counts = service.runSweep(NOW);

    assertThat(counts).isEqualTo(new SellerPayoutService.PayoutSweepCounts(1, 0, 0, 0));
    assertThat(count("payouts.promoted")).isEqualTo(1.0);
    assertThat(count("payouts.paid")).isZero();
    assertThat(count("payouts.skipped")).isZero();
    assertThat(count("payouts.held")).isZero();
  }

  @Test
  void paidRowMovesOnlyThePaidCounter() {
    when(payouts.findIdsByStatus(PayoutStatus.PENDING)).thenReturn(List.of());
    when(payouts.findIdsByStatus(PayoutStatus.PAYABLE)).thenReturn(List.of(2L));
    when(transitions.markPaid(eq(2L), any())).thenReturn(true);

    SellerPayoutService.PayoutSweepCounts counts = service.runSweep(NOW);

    assertThat(counts).isEqualTo(new SellerPayoutService.PayoutSweepCounts(0, 1, 0, 0));
    assertThat(count("payouts.paid")).isEqualTo(1.0);
    assertThat(count("payouts.promoted")).isZero();
    assertThat(count("payouts.skipped")).isZero();
    assertThat(count("payouts.held")).isZero();
  }

  @Test
  void windowNotPassedCountsAsSkipped() {
    when(payouts.findIdsByStatus(PayoutStatus.PENDING)).thenReturn(List.of(3L));
    when(payouts.findIdsByStatus(PayoutStatus.PAYABLE)).thenReturn(List.of());
    when(transitions.promoteToPayable(eq(3L), any())).thenReturn(false);

    SellerPayoutService.PayoutSweepCounts counts = service.runSweep(NOW);

    assertThat(counts).isEqualTo(new SellerPayoutService.PayoutSweepCounts(0, 0, 1, 0));
    assertThat(count("payouts.skipped")).isEqualTo(1.0);
    assertThat(count("payouts.promoted")).isZero();
    assertThat(count("payouts.paid")).isZero();
    assertThat(count("payouts.held")).isZero();
  }

  @Test
  void lostRaceCountsAsSkipped() {
    when(payouts.findIdsByStatus(PayoutStatus.PENDING)).thenReturn(List.of(4L));
    when(payouts.findIdsByStatus(PayoutStatus.PAYABLE)).thenReturn(List.of());
    when(transitions.promoteToPayable(eq(4L), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException("SellerPayout", 4L));

    SellerPayoutService.PayoutSweepCounts counts = service.runSweep(NOW);

    assertThat(counts).isEqualTo(new SellerPayoutService.PayoutSweepCounts(0, 0, 1, 0));
    assertThat(count("payouts.skipped")).isEqualTo(1.0);
    assertThat(count("payouts.promoted")).isZero();
  }

  @Test
  void heldRowMovesOnlyTheHeldCounter() {
    when(payouts.findIdsByStatus(PayoutStatus.PENDING)).thenReturn(List.of(5L));
    when(payouts.findIdsByStatus(PayoutStatus.PAYABLE)).thenReturn(List.of(6L));
    when(transitions.isSellerHeld(5L)).thenReturn(true);
    when(transitions.isSellerHeld(6L)).thenReturn(true);

    SellerPayoutService.PayoutSweepCounts counts = service.runSweep(NOW);

    assertThat(counts).isEqualTo(new SellerPayoutService.PayoutSweepCounts(0, 0, 0, 2));
    assertThat(count("payouts.held")).isEqualTo(2.0);
    assertThat(count("payouts.promoted")).isZero();
    assertThat(count("payouts.paid")).isZero();
    assertThat(count("payouts.skipped")).isZero();
  }

  @Test
  void mixedSweepCountersMatchReturnedCounts() {
    when(payouts.findIdsByStatus(PayoutStatus.PENDING)).thenReturn(List.of(11L, 12L, 13L, 14L));
    when(payouts.findIdsByStatus(PayoutStatus.PAYABLE)).thenReturn(List.of(21L, 22L, 23L));
    when(transitions.isSellerHeld(12L)).thenReturn(true);
    when(transitions.isSellerHeld(22L)).thenReturn(true);
    when(transitions.promoteToPayable(eq(11L), any())).thenReturn(true);
    when(transitions.promoteToPayable(eq(13L), any())).thenReturn(false);
    when(transitions.promoteToPayable(eq(14L), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException("SellerPayout", 14L));
    when(transitions.markPaid(eq(21L), any())).thenReturn(true);
    when(transitions.markPaid(eq(23L), any())).thenReturn(false);

    SellerPayoutService.PayoutSweepCounts counts = service.runSweep(NOW);

    assertThat(counts).isEqualTo(new SellerPayoutService.PayoutSweepCounts(1, 1, 3, 2));
    assertThat(count("payouts.promoted")).isEqualTo(1.0);
    assertThat(count("payouts.paid")).isEqualTo(1.0);
    assertThat(count("payouts.skipped")).isEqualTo(3.0);
    assertThat(count("payouts.held")).isEqualTo(2.0);
  }

  @Test
  void sweepWithoutLeaseMovesNoCounter() {
    JobLockGuard lock = mock(JobLockGuard.class);
    when(lock.tryAcquire(SellerPayoutService.LOCK_NAME)).thenReturn(false);
    SellerPayoutService leased = new SellerPayoutService(
        payouts,
        mock(SellerPayoutAdjustmentRepository.class),
        transitions,
        Clock.fixed(NOW, ZoneOffset.UTC),
        lock,
        metrics);

    SellerPayoutService.PayoutSweepCounts counts = leased.runSweep(NOW);

    assertThat(counts).isEqualTo(new SellerPayoutService.PayoutSweepCounts(0, 0, 0, 0));
    assertThat(count("payouts.promoted")).isZero();
    assertThat(count("payouts.paid")).isZero();
    assertThat(count("payouts.skipped")).isZero();
    assertThat(count("payouts.held")).isZero();
  }
}
