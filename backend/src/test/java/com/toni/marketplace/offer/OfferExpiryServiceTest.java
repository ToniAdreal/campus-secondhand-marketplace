package com.toni.marketplace.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
  void worklistAsksForPendingOffersAtTheCurrentInstant() {
    when(offers.findIdsByStatusAndExpiresAtBefore(any(), any(), any()))
        .thenReturn(List.of());

    clock.advance(Duration.ofHours(49));
    expiry.expireStaleOffers();

    verify(offers, times(1)).findIdsByStatusAndExpiresAtBefore(
        eq(OfferStatus.PENDING), eq(Instant.EPOCH.plus(Duration.ofHours(49))),
        any(Pageable.class));
  }

  @Test
  void happyPath_expiresEveryRowAndCountsThem() {
    when(offers.findIdsByStatusAndExpiresAtBefore(any(), any(), any()))
        .thenReturn(List.of(1L, 2L));
    when(offerService.expireStaleOffer(eq(1L), any())).thenReturn(true);
    when(offerService.expireStaleOffer(eq(2L), any())).thenReturn(true);

    OfferExpiryService.ExpiryCounts counts = expiry.expireStaleOffers();

    assertThat(counts.expired()).isEqualTo(2);
    assertThat(counts.skipped()).isZero();
  }

  @Test
  void answeredOrRacingRowsAreSkippedNeverFailed() {
    when(offers.findIdsByStatusAndExpiresAtBefore(any(), any(), any()))
        .thenReturn(List.of(1L, 2L, 3L));
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
}
