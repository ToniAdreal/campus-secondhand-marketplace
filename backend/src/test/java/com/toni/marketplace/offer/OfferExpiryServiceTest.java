package com.toni.marketplace.offer;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Pure unit tests for {@link OfferExpiryService} with a {@link
 * MutableClock} standing in for wall time, mirroring
 * {@code PendingOrderExpiryServiceTest}: these pin the worklist query
 * arguments, the batch counting and the per-row skip semantics, not the
 * row transaction itself.
 */
@ExtendWith(MockitoExtension.class)
class OfferExpiryServiceTest {

  @Mock
  private PriceOfferRepository offers;

  @Mock
  private OfferService offerService;

  @Mock
  private JobLockGuard jobLock;

  private MutableClock clock;
  private OfferProperties props;
  private OfferExpiryService expiry;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.EPOCH);
    props = new OfferProperties();
    expiry = new OfferExpiryService(offers, offerService, clock);
  }

  @Test
  void defaultTtlIsDocumented48Hours() {
    assertThat(props.getPendingTtl()).isEqualTo(Duration.ofHours(48));
  }

  @Test
  void worklistAsksForPendingAndCounteredOffersAtTheCurrentInstant() {
    when(offers.findIdsByStatusAndExpiresAtBefore(any(), any(), any()))
        .thenReturn(List.of());

    clock.advance(Duration.ofHours(49));
    expiry.expireStaleOffers();

    // Both live states are swept on the same deadline (#193).
    verify(offers, times(1)).findIdsByStatusAndExpiresAtBefore(
        eq(OfferStatus.PENDING), eq(Instant.EPOCH.plus(Duration.ofHours(49))),
        any(Pageable.class));
    verify(offers, times(1)).findIdsByStatusAndExpiresAtBefore(
        eq(OfferStatus.COUNTERED), eq(Instant.EPOCH.plus(Duration.ofHours(49))),
        any(Pageable.class));
  }

  @Test
  void happyPath_expiresEveryRowAndCountsThem() {
    // One stale PENDING row and one stale COUNTERED row (#193).
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.PENDING), any(), any()))
        .thenReturn(List.of(1L));
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.COUNTERED), any(), any()))
        .thenReturn(List.of(2L));
    when(offerService.expireStaleOffer(eq(1L), any())).thenReturn(true);
    when(offerService.expireStaleOffer(eq(2L), any())).thenReturn(true);

    OfferExpiryService.ExpiryCounts counts = expiry.expireStaleOffers();

    assertThat(counts.expired()).isEqualTo(2);
    assertThat(counts.skipped()).isZero();
  }

  @Test
  void answeredOrRacingRowsAreSkippedNeverFailed() {
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.PENDING), any(), any()))
        .thenReturn(List.of(1L, 2L, 3L));
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.COUNTERED), any(), any()))
        .thenReturn(List.of());
    // Row 1 was answered between the scan and its row transaction.
    when(offerService.expireStaleOffer(eq(1L), any())).thenReturn(false);
    // Row 2 lost its @Version race to a concurrent answer.
    when(offerService.expireStaleOffer(eq(2L), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException("offer", 2L));
    when(offerService.expireStaleOffer(eq(3L), any())).thenReturn(true);

    OfferExpiryService.ExpiryCounts counts = expiry.expireStaleOffers();

    assertThat(counts.expired()).isEqualTo(1);
    assertThat(counts.skipped()).isEqualTo(2);
  }

  @Test
  void lockHeld_returnsZeroCountsWithoutTouchingRepositories() {
    when(jobLock.tryAcquire(OfferExpiryService.LOCK_NAME)).thenReturn(false);
    OfferExpiryService locked = new OfferExpiryService(offers, offerService, clock, jobLock);

    OfferExpiryService.ExpiryCounts counts = locked.expireStaleOffers();

    assertThat(counts).isEqualTo(new OfferExpiryService.ExpiryCounts(0, 0));
    verify(jobLock).tryAcquire(OfferExpiryService.LOCK_NAME);
    verifyNoInteractions(offers, offerService);
    // A lease never held is never released.
    verify(jobLock, never()).release(any());
  }

  @Test
  void happyPath_releasesTheLease() {
    when(jobLock.tryAcquire(OfferExpiryService.LOCK_NAME)).thenReturn(true);
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.PENDING), any(), any()))
        .thenReturn(List.of(1L));
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.COUNTERED), any(), any()))
        .thenReturn(List.of(2L));
    when(offerService.expireStaleOffer(eq(1L), any())).thenReturn(true);
    when(offerService.expireStaleOffer(eq(2L), any())).thenReturn(true);
    OfferExpiryService guarded = new OfferExpiryService(offers, offerService, clock, jobLock);

    OfferExpiryService.ExpiryCounts counts = guarded.expireStaleOffers();

    assertThat(counts).isEqualTo(new OfferExpiryService.ExpiryCounts(2, 0));
    verify(jobLock).release(OfferExpiryService.LOCK_NAME);
  }

  @Test
  void scanFailure_stillReleasesTheLease() {
    when(jobLock.tryAcquire(OfferExpiryService.LOCK_NAME)).thenReturn(true);
    when(offers.findIdsByStatusAndExpiresAtBefore(any(), any(), any()))
        .thenThrow(new IllegalStateException("db down"));
    OfferExpiryService guarded = new OfferExpiryService(offers, offerService, clock, jobLock);

    assertThrows(IllegalStateException.class, guarded::expireStaleOffers);
    verify(jobLock).release(OfferExpiryService.LOCK_NAME);
  }

  @Test
  void unexpectedFailureOnOneRow_skipsItAndContinuesTheBatch() {
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.PENDING), any(), any()))
        .thenReturn(List.of(1L, 2L));
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.COUNTERED), any(), any()))
        .thenReturn(List.of());
    // Row 1 blows up in its row transaction with something that is neither
    // an answered row nor an optimistic-lock loss: the defensive catch
    // skips it and the batch still expires row 2.
    when(offerService.expireStaleOffer(eq(1L), any()))
        .thenThrow(new IllegalStateException("row 1 broken"));
    when(offerService.expireStaleOffer(eq(2L), any())).thenReturn(true);

    OfferExpiryService.ExpiryCounts counts = expiry.expireStaleOffers();

    assertThat(counts).isEqualTo(new OfferExpiryService.ExpiryCounts(1, 1));
  }

  @Test
  void fullPage_triggersAnotherScan_partialPageStops() {
    List<Long> fullPage = LongStream.rangeClosed(1, OfferExpiryService.BATCH_SIZE)
        .boxed().toList();
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.PENDING), any(), any()))
        .thenReturn(fullPage)   // first scan: full page → scan again
        .thenReturn(List.of()); // second scan: empty → stop
    when(offers.findIdsByStatusAndExpiresAtBefore(eq(OfferStatus.COUNTERED), any(), any()))
        .thenReturn(List.of());
    when(offerService.expireStaleOffer(any(), any())).thenReturn(true);

    OfferExpiryService.ExpiryCounts counts = expiry.expireStaleOffers();

    verify(offers, times(2)).findIdsByStatusAndExpiresAtBefore(
        eq(OfferStatus.PENDING), any(), any(Pageable.class));
    assertThat(counts).isEqualTo(
        new OfferExpiryService.ExpiryCounts(OfferExpiryService.BATCH_SIZE, 0));
  }
}
