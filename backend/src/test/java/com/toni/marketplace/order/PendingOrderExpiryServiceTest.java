package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.job.JobLockGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Pure unit tests for {@link PendingOrderExpiryService} with a
 * {@link MutableClock} standing in for wall time. The repositories and
 * {@link OrderService} are mocked — these tests pin the TTL arithmetic, the
 * batch loop and the per-row skip semantics, not the JPQL or the row
 * transaction (covered by {@link OrderExpiryTest} against a real database).
 */
@ExtendWith(MockitoExtension.class)
class PendingOrderExpiryServiceTest {

  @Mock
  private OrderRepository orders;

  @Mock
  private OrderService orderService;

  @Mock
  private JobLockGuard jobLock;

  private MutableClock clock;
  private OrderExpiryProperties props;
  private PendingOrderExpiryService expiry;

  @BeforeEach
  void setUp() {
    // Fixed epoch start keeps the TTL arithmetic legible; production starts
    // at wall-clock time, which the math treats identically.
    clock = new MutableClock(Instant.EPOCH);
    props = new OrderExpiryProperties();
    expiry = new PendingOrderExpiryService(orders, orderService, props, clock);
  }

  @Test
  void defaultsAreDocumented() {
    assertThat(props.getPendingTtl()).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  void cutoffIsNowMinusTtl() {
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenReturn(List.of());

    clock.advance(Duration.ofMinutes(40));
    expiry.expireStalePendingOrders();

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(orders, times(1)).findIdsByStatusAndCreatedAtBefore(
        eq(OrderStatus.PENDING), cutoffCaptor.capture(), any(Pageable.class));
    // 40 minutes in, 30-minute TTL → the cutoff sits 10 minutes after epoch.
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(10)));
  }

  @Test
  void happyPath_expiresEveryRowAndCountsThem() {
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenReturn(List.of(1L, 2L));
    when(orderService.expireStaleOrder(eq(1L), any())).thenReturn(true);
    when(orderService.expireStaleOrder(eq(2L), any())).thenReturn(true);

    clock.advance(Duration.ofMinutes(40));
    PendingOrderExpiryService.ExpiryCounts counts = expiry.expireStalePendingOrders();

    assertThat(counts).isEqualTo(new PendingOrderExpiryService.ExpiryCounts(2, 0));
  }

  @Test
  void rowThatBecameNonStale_countsAsSkipped() {
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenReturn(List.of(7L));
    // Paid between the scan and the row transaction: the per-row re-check
    // returns false and nothing is touched.
    when(orderService.expireStaleOrder(eq(7L), any())).thenReturn(false);

    clock.advance(Duration.ofMinutes(40));
    PendingOrderExpiryService.ExpiryCounts counts = expiry.expireStalePendingOrders();

    assertThat(counts).isEqualTo(new PendingOrderExpiryService.ExpiryCounts(0, 1));
  }

  @Test
  void versionConflictOnOneRow_skipsItAndContinuesTheBatch() {
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenReturn(List.of(1L, 2L));
    // Row 1 loses a race to a concurrent pay: its row transaction throws the
    // optimistic-lock failure, the job must skip it and still expire row 2.
    when(orderService.expireStaleOrder(eq(1L), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException("order", 1L));
    when(orderService.expireStaleOrder(eq(2L), any())).thenReturn(true);

    clock.advance(Duration.ofMinutes(40));
    PendingOrderExpiryService.ExpiryCounts counts = expiry.expireStalePendingOrders();

    assertThat(counts).isEqualTo(new PendingOrderExpiryService.ExpiryCounts(1, 1));
    verify(orderService, times(1)).expireStaleOrder(eq(2L), any());
  }

  @Test
  void fullPage_triggersAnotherScan_partialPageStops() {
    List<Long> fullPage = LongStream.rangeClosed(1, PendingOrderExpiryService.BATCH_SIZE)
        .boxed().toList();
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenReturn(fullPage)   // first scan: full page → scan again
        .thenReturn(List.of()); // second scan: empty → stop
    when(orderService.expireStaleOrder(any(), any())).thenReturn(true);

    clock.advance(Duration.ofMinutes(40));
    PendingOrderExpiryService.ExpiryCounts counts = expiry.expireStalePendingOrders();

    verify(orders, times(2))
        .findIdsByStatusAndCreatedAtBefore(eq(OrderStatus.PENDING), any(), any(Pageable.class));
    assertThat(counts).isEqualTo(
        new PendingOrderExpiryService.ExpiryCounts(PendingOrderExpiryService.BATCH_SIZE, 0));
  }

  @Test
  void lockHeld_returnsZeroCountsWithoutTouchingRepositories() {
    when(jobLock.tryAcquire(PendingOrderExpiryService.LOCK_NAME)).thenReturn(false);
    PendingOrderExpiryService locked =
        new PendingOrderExpiryService(orders, orderService, props, clock, jobLock);

    PendingOrderExpiryService.ExpiryCounts counts = locked.expireStalePendingOrders();

    assertThat(counts).isEqualTo(new PendingOrderExpiryService.ExpiryCounts(0, 0));
    verify(jobLock).tryAcquire(PendingOrderExpiryService.LOCK_NAME);
    verifyNoInteractions(orders, orderService);
    // A lease never held is never released.
    verify(jobLock, never()).release(any());
  }

  @Test
  void happyPath_releasesTheLease() {
    when(jobLock.tryAcquire(PendingOrderExpiryService.LOCK_NAME)).thenReturn(true);
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenReturn(List.of(1L, 2L));
    when(orderService.expireStaleOrder(eq(1L), any())).thenReturn(true);
    when(orderService.expireStaleOrder(eq(2L), any())).thenReturn(true);
    PendingOrderExpiryService guarded =
        new PendingOrderExpiryService(orders, orderService, props, clock, jobLock);

    clock.advance(Duration.ofMinutes(40));
    PendingOrderExpiryService.ExpiryCounts counts = guarded.expireStalePendingOrders();

    assertThat(counts).isEqualTo(new PendingOrderExpiryService.ExpiryCounts(2, 0));
    verify(jobLock).release(PendingOrderExpiryService.LOCK_NAME);
  }

  @Test
  void scanFailure_stillReleasesTheLease() {
    when(jobLock.tryAcquire(PendingOrderExpiryService.LOCK_NAME)).thenReturn(true);
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenThrow(new IllegalStateException("db down"));
    PendingOrderExpiryService guarded =
        new PendingOrderExpiryService(orders, orderService, props, clock, jobLock);

    assertThrows(IllegalStateException.class, guarded::expireStalePendingOrders);
    verify(jobLock).release(PendingOrderExpiryService.LOCK_NAME);
  }

  @Test
  void unexpectedFailureOnOneRow_skipsItAndContinuesTheBatch() {
    when(orders.findIdsByStatusAndCreatedAtBefore(any(), any(), any()))
        .thenReturn(List.of(1L, 2L));
    // Row 1 blows up in its row transaction with something that is neither
    // an answered row nor an optimistic-lock loss: the defensive catch
    // skips it and the batch still expires row 2.
    when(orderService.expireStaleOrder(eq(1L), any()))
        .thenThrow(new IllegalStateException("row 1 broken"));
    when(orderService.expireStaleOrder(eq(2L), any())).thenReturn(true);

    clock.advance(Duration.ofMinutes(40));
    PendingOrderExpiryService.ExpiryCounts counts = expiry.expireStalePendingOrders();

    assertThat(counts).isEqualTo(new PendingOrderExpiryService.ExpiryCounts(1, 1));
    verify(orderService, times(1)).expireStaleOrder(eq(2L), any());
  }
}
