package com.toni.marketplace.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.auth.DataExportGrantRepository;
import com.toni.marketplace.auth.EmailChangeTokenRepository;
import com.toni.marketplace.auth.EmailVerificationTokenRepository;
import com.toni.marketplace.auth.LoginEventRepository;
import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.auth.PasskeyChallengeRepository;
import com.toni.marketplace.auth.PasswordResetTokenRepository;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.TotpRecoveryCodeRepository;
import com.toni.marketplace.dispute.DisputeEvidenceRepository;
import com.toni.marketplace.item.ItemPhotoRepository;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemService;
import com.toni.marketplace.item.ListingExpiryService;
import com.toni.marketplace.item.RecentlyViewedRepository;
import com.toni.marketplace.item.SavedSearchRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationService;
import com.toni.marketplace.offer.OfferExpiryService;
import com.toni.marketplace.offer.OfferService;
import com.toni.marketplace.offer.PriceOfferRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderExpiryProperties;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderService;
import com.toni.marketplace.order.PendingOrderExpiryService;
import com.toni.marketplace.order.WebhookEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wiring pin for the single-instance guard (backlog #215): every one of
 * the six mutating {@code @Scheduled} jobs consults the guard under its
 * own distinct lease name BEFORE touching any collaborator — with the
 * lease denied, the firing returns the job's zero result and no
 * repository or service is ever called. A seventh job wired later, or
 * one of the six quietly losing its guard, fails here. The SSE
 * heartbeat ({@code NotificationStreamService}) is intentionally not
 * in this list: it mutates nothing outside its own JVM.
 */
class JobLockWiringTest {

  private MutableClock clock;
  private JobLockGuard denied;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    denied = mock(JobLockGuard.class);
    when(denied.tryAcquire(anyString())).thenReturn(false);
  }

  @Test
  void theSixJobsGuardUnderSixDistinctNames() {
    assertThat(Set.of(
        PendingOrderExpiryService.LOCK_NAME,
        OfferExpiryService.LOCK_NAME,
        SavedSearchMatchingService.LOCK_NAME,
        DataRetentionService.LOCK_NAME,
        ListingExpiryService.LOCK_NAME,
        OrphanUploadSweeper.LOCK_NAME))
        .containsExactlyInAnyOrder(
            "pending-order-expiry", "offer-expiry", "saved-search-matching",
            "data-retention", "listing-expiry", "orphan-upload-sweep");
  }

  @Test
  void pendingOrderExpirySkipsWhenTheLeaseIsDenied() {
    OrderRepository orders = mock(OrderRepository.class);
    OrderService orderService = mock(OrderService.class);
    PendingOrderExpiryService job = new PendingOrderExpiryService(
        orders, orderService, new OrderExpiryProperties(), clock, denied);

    assertThat(job.expireStalePendingOrders())
        .isEqualTo(new PendingOrderExpiryService.ExpiryCounts(0, 0));
    verify(denied).tryAcquire(PendingOrderExpiryService.LOCK_NAME);
    verifyNoInteractions(orders, orderService);
  }

  @Test
  void offerExpirySkipsWhenTheLeaseIsDenied() {
    PriceOfferRepository offers = mock(PriceOfferRepository.class);
    OfferService offerService = mock(OfferService.class);
    OfferExpiryService job = new OfferExpiryService(offers, offerService, clock, denied);

    assertThat(job.expireStaleOffers())
        .isEqualTo(new OfferExpiryService.ExpiryCounts(0, 0));
    verify(denied).tryAcquire(OfferExpiryService.LOCK_NAME);
    verifyNoInteractions(offers, offerService);
  }

  @Test
  void savedSearchMatchingSkipsWhenTheLeaseIsDenied() {
    SavedSearchRepository savedSearches = mock(SavedSearchRepository.class);
    ItemService itemService = mock(ItemService.class);
    NotificationService notifications = mock(NotificationService.class);
    SavedSearchMatchingService job = new SavedSearchMatchingService(
        savedSearches, itemService, notifications, denied);

    assertThat(job.matchSavedSearches())
        .isEqualTo(new SavedSearchMatchingService.MatchCounts(0, 0, 0, 0));
    verify(denied).tryAcquire(SavedSearchMatchingService.LOCK_NAME);
    verifyNoInteractions(savedSearches, itemService, notifications);
  }

  @Test
  void dataRetentionSkipsWhenTheLeaseIsDenied() {
    RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    IdempotencyKeyRepository idempotencyKeys = mock(IdempotencyKeyRepository.class);
    PasswordResetTokenRepository passwordResetTokens = mock(PasswordResetTokenRepository.class);
    AuditLogRepository auditLog = mock(AuditLogRepository.class);
    WebhookEventRepository webhookEvents = mock(WebhookEventRepository.class);
    EmailVerificationTokenRepository verificationTokens =
        mock(EmailVerificationTokenRepository.class);
    LoginEventRepository loginEvents = mock(LoginEventRepository.class);
    RecentlyViewedRepository recentlyViewed = mock(RecentlyViewedRepository.class);
    NotificationRepository notifications = mock(NotificationRepository.class);
    TotpRecoveryCodeRepository totpRecoveryCodes = mock(TotpRecoveryCodeRepository.class);
    PasskeyChallengeRepository passkeyChallenges = mock(PasskeyChallengeRepository.class);
    EmailChangeTokenRepository emailChangeTokens = mock(EmailChangeTokenRepository.class);
    DataExportGrantRepository dataExportGrants = mock(DataExportGrantRepository.class);
    DataRetentionService job = new DataRetentionService(
        refreshTokens, idempotencyKeys, passwordResetTokens, auditLog, webhookEvents,
        verificationTokens, loginEvents, recentlyViewed, notifications,
        totpRecoveryCodes, passkeyChallenges, emailChangeTokens, dataExportGrants,
        new CleanupProperties(), clock, denied);

    assertThat(job.purgeStaleData())
        .isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    verify(denied).tryAcquire(DataRetentionService.LOCK_NAME);
    verifyNoInteractions(refreshTokens, idempotencyKeys, passwordResetTokens, auditLog,
        webhookEvents, verificationTokens, loginEvents, recentlyViewed, notifications,
        totpRecoveryCodes, passkeyChallenges, emailChangeTokens, dataExportGrants);
  }

  @Test
  void listingExpirySkipsWhenTheLeaseIsDenied() {
    ItemRepository items = mock(ItemRepository.class);
    ItemService itemService = mock(ItemService.class);
    ListingExpiryService job = new ListingExpiryService(items, itemService, clock, denied);

    assertThat(job.expireStaleListings())
        .isEqualTo(new ListingExpiryService.ExpiryCounts(0, 0));
    verify(denied).tryAcquire(ListingExpiryService.LOCK_NAME);
    verifyNoInteractions(items, itemService);
  }

  @Test
  void orphanUploadSweepSkipsWhenTheLeaseIsDenied() {
    ItemRepository items = mock(ItemRepository.class);
    ItemPhotoRepository itemPhotos = mock(ItemPhotoRepository.class);
    DisputeEvidenceRepository disputeEvidence = mock(DisputeEvidenceRepository.class);
    OrphanUploadSweeper job = new OrphanUploadSweeper(
        items, itemPhotos, disputeEvidence, "/tmp/never-touched-by-wiring-test",
        Duration.ofHours(24), clock, denied);

    assertThat(job.sweepOrphanUploads()).isEqualTo(0);
    verify(denied).tryAcquire(OrphanUploadSweeper.LOCK_NAME);
    verifyNoInteractions(items, itemPhotos, disputeEvidence);
  }
}
