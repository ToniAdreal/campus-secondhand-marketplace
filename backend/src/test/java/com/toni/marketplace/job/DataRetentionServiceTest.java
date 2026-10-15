package com.toni.marketplace.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.auth.EmailVerificationTokenRepository;
import com.toni.marketplace.auth.LoginEventRepository;
import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.auth.PasswordResetTokenRepository;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.IdempotencyStatus;
import com.toni.marketplace.order.WebhookEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * Pure unit tests for the {@link DataRetentionService} purge logic, with a
 * {@link MutableClock} standing in for wall time. The repositories are
 * mocked — these tests pin the retention arithmetic, the terminal-status
 * filter and the batch loop, not the JPQL (slice tests would need a Spring
 * context; the {@code findStaleIds}/{@code findTerminalIdsBefore} queries
 * are exercised wherever the app boots).
 */
@ExtendWith(MockitoExtension.class)
class DataRetentionServiceTest {

  @Mock
  private RefreshTokenRepository refreshTokens;

  @Mock
  private IdempotencyKeyRepository idempotencyKeys;

  @Mock
  private PasswordResetTokenRepository passwordResetTokens;

  @Mock
  private AuditLogRepository auditLog;

  @Mock
  private WebhookEventRepository webhookEvents;

  @Mock
  private EmailVerificationTokenRepository emailVerificationTokens;

  @Mock
  private LoginEventRepository loginEvents;

  private MutableClock clock;
  private CleanupProperties props;
  private DataRetentionService purge;

  @BeforeEach
  void setUp() {
    // Fixed epoch start keeps the retention arithmetic legible; production
    // starts at wall-clock time, which the math treats identically.
    clock = new MutableClock(Instant.EPOCH);
    props = new CleanupProperties();
    purge = new DataRetentionService(refreshTokens, idempotencyKeys,
        passwordResetTokens, auditLog, webhookEvents, emailVerificationTokens,
        loginEvents, props, clock);
  }

  @Test
  void defaultsAreDocumented() {
    assertThat(props.getRefreshTokenRetention()).isEqualTo(30);
    assertThat(props.getIdempotencyRetention()).isEqualTo(90);
    assertThat(props.getPasswordResetRetention()).isEqualTo(30);
    // The audit trail is the security record: its window is materially
    // longer than every token window (backlog #118).
    assertThat(props.getAuditLogRetention()).isEqualTo(365);
    // Webhook deliveries are reconciliation evidence: kept longer than
    // the token windows, shorter than the audit trail (backlog #152).
    assertThat(props.getWebhookEventRetention()).isEqualTo(180);
    // Aligned with the password-reset window — the two token tables are
    // siblings (backlog #152).
    assertThat(props.getEmailVerificationTokenRetention()).isEqualTo(30);
    // Login history is sign-in evidence: aligned with the webhook
    // window (backlog #171).
    assertThat(props.getLoginEventRetention()).isEqualTo(180);
  }

  @Test
  void purgeDeletesStaleRefreshTokensAfterRetentionWindow() {
    // One partial page: the batch loop stops after it, so findStaleIds is
    // called exactly once.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of(1L, 2L));
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(31));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(2, 0, 0, 0, 0, 0, 0));
    verify(refreshTokens, times(1)).deleteAllByIdInBatch(List.of(1L, 2L));

    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(refreshTokens, times(1))
        .findStaleIds(nowCaptor.capture(), cutoffCaptor.capture(), any(Pageable.class));
    assertThat(nowCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(31)));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
  }

  @Test
  void purgeNeverTargetsInProgressKeys() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    clock.advance(Duration.ofDays(365));

    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts.idempotencyKeys()).isEqualTo(0);
    ArgumentCaptor<Set<IdempotencyStatus>> statusCaptor =
        ArgumentCaptor.forClass(Set.class);
    verify(idempotencyKeys).findTerminalIdsBefore(statusCaptor.capture(), any(), any());
    assertThat(statusCaptor.getValue())
        .containsExactlyInAnyOrder(IdempotencyStatus.COMPLETED, IdempotencyStatus.FAILED);
  }

  @Test
  void purgeDeletesTerminalKeysInBatches() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    List<Long> fullBatch = LongStream.range(0, DataRetentionService.BATCH_SIZE)
        .boxed().toList();
    List<Long> partialBatch = LongStream.range(1000, 1200).boxed().toList();
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any()))
        .thenReturn(fullBatch, partialBatch);

    clock.advance(Duration.ofDays(91));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 700, 0, 0, 0, 0, 0));
    verify(idempotencyKeys, times(2)).deleteAllByIdInBatch(any());
    verify(idempotencyKeys).deleteAllByIdInBatch(fullBatch);
    verify(idempotencyKeys).deleteAllByIdInBatch(partialBatch);

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(idempotencyKeys, times(2))
        .findTerminalIdsBefore(any(), cutoffCaptor.capture(), any(Pageable.class));
    assertThat(cutoffCaptor.getAllValues())
        .allSatisfy(cutoff -> assertThat(cutoff)
            .isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1))));
  }

  @Test
  void emptyReposDeleteNothing() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(365));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 0, 0, 0));
    verify(refreshTokens, never()).deleteAllByIdInBatch(any());
    verify(idempotencyKeys, never()).deleteAllByIdInBatch(any());
    verify(passwordResetTokens, never()).deleteAllByIdInBatch(any());
    verify(auditLog, never()).deleteAllByIdInBatch(any());
    verify(webhookEvents, never()).deleteAllByIdInBatch(any());
    verify(emailVerificationTokens, never()).deleteAllByIdInBatch(any());
    verify(loginEvents, never()).deleteAllByIdInBatch(any());
  }

  @Test
  void customRetentionShrinksTheCutoffWindow() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    props.setRefreshTokenRetention(7);
    props.setIdempotencyRetention(14);
    props.setPasswordResetRetention(5);
    props.setAuditLogRetention(9);
    props.setWebhookEventRetention(20);
    props.setEmailVerificationTokenRetention(6);
    props.setLoginEventRetention(8);

    clock.advance(Duration.ofDays(10));
    purge.purgeStaleData();

    ArgumentCaptor<Instant> tokenCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(refreshTokens).findStaleIds(any(), tokenCutoff.capture(), any(Pageable.class));
    assertThat(tokenCutoff.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(3)));

    ArgumentCaptor<Instant> keyCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(idempotencyKeys).findTerminalIdsBefore(any(), keyCutoff.capture(), any(Pageable.class));
    // 10 days elapsed < 14-day key retention: the query still runs (the
    // repository decides staleness), but the cutoff predates creation, so
    // nothing can match yet.
    assertThat(keyCutoff.getValue()).isEqualTo(Instant.EPOCH.minus(Duration.ofDays(4)));

    ArgumentCaptor<Instant> resetCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(passwordResetTokens).findPurgeableIds(any(), resetCutoff.capture(),
        any(Pageable.class));
    assertThat(resetCutoff.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(5)));

    ArgumentCaptor<Instant> auditCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(auditLog).findPurgeableIds(auditCutoff.capture(), any(Pageable.class));
    assertThat(auditCutoff.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));

    ArgumentCaptor<Instant> webhookCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(webhookEvents).findPurgeableIds(webhookCutoff.capture(), any(Pageable.class));
    // 10 days elapsed < 20-day webhook retention: the cut-off predates
    // creation, so nothing can match yet.
    assertThat(webhookCutoff.getValue()).isEqualTo(Instant.EPOCH.minus(Duration.ofDays(10)));

    ArgumentCaptor<Instant> verificationCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(emailVerificationTokens).findPurgeableIds(any(), verificationCutoff.capture(),
        any(Pageable.class));
    assertThat(verificationCutoff.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(4)));

    ArgumentCaptor<Instant> loginEventCutoff = ArgumentCaptor.forClass(Instant.class);
    verify(loginEvents).findPurgeableIds(loginEventCutoff.capture(), any(Pageable.class));
    assertThat(loginEventCutoff.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(2)));
  }

  @Test
  void purgeDeletesDeadPasswordResetTokensPastRetention() {
    // The repository query is what decides "dead" (used, or expired as of
    // now); here it hands back the used row and the expired-never-used row
    // that both predate the retention cut-off.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any()))
        .thenReturn(List.of(7L, 8L));

    clock.advance(Duration.ofDays(31));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 2, 0, 0, 0, 0));
    assertThat(counts.passwordResetTokens()).isEqualTo(2);
    verify(passwordResetTokens, times(1)).deleteAllByIdInBatch(List.of(7L, 8L));

    // now = EPOCH + 31 d; the 30-day default retention puts the creation
    // cut-off at EPOCH + 1 d — anything created later is still audit
    // evidence and must survive.
    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(passwordResetTokens, times(1))
        .findPurgeableIds(nowCaptor.capture(), cutoffCaptor.capture(), any(Pageable.class));
    assertThat(nowCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(31)));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
  }

  @Test
  void purgeNeverDeletesWhatTheResetQueryDidNotSelect() {
    // A still-valid unused token (an in-flight reset) and dead rows still
    // inside the retention window both fail the repository predicate, so
    // the query returns nothing and the service deletes nothing — the
    // service itself never widens the selection.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(365));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts.passwordResetTokens()).isEqualTo(0);
    verify(passwordResetTokens, never()).deleteAllByIdInBatch(any());
    // The query still receives the live clock reading, so "expired as of
    // now" is judged at purge time, not at some stale snapshot.
    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(passwordResetTokens)
        .findPurgeableIds(nowCaptor.capture(), any(), any(Pageable.class));
    assertThat(nowCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(365)));
  }

  @Test
  void purgeDeletesPasswordResetTokensInBatches() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    List<Long> fullBatch = LongStream.range(0, DataRetentionService.BATCH_SIZE)
        .boxed().toList();
    List<Long> partialBatch = LongStream.range(2000, 2100).boxed().toList();
    when(passwordResetTokens.findPurgeableIds(any(), any(), any()))
        .thenReturn(fullBatch, partialBatch);

    clock.advance(Duration.ofDays(31));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 600, 0, 0, 0, 0));
    verify(passwordResetTokens, times(2)).deleteAllByIdInBatch(any());
    verify(passwordResetTokens).deleteAllByIdInBatch(fullBatch);
    verify(passwordResetTokens).deleteAllByIdInBatch(partialBatch);
  }

  @Test
  void purgeDeletesAuditRowsPastRetentionWindow() {
    // The repository query selects by created_at alone; here it hands
    // back the two rows that predate the retention cut-off. Rows inside
    // the window fail the predicate, so they are never handed back and
    // never deleted — the service deletes exactly what the query selects.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of(11L, 12L));

    clock.advance(Duration.ofDays(366));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 2, 0, 0, 0));
    assertThat(counts.auditLogRows()).isEqualTo(2);
    verify(auditLog, times(1)).deleteAllByIdInBatch(List.of(11L, 12L));

    // now = EPOCH + 366 d; the 365-day default retention puts the
    // creation cut-off at EPOCH + 1 d — anything created later is still
    // inside the append-only window and must survive.
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(auditLog, times(1)).findPurgeableIds(cutoffCaptor.capture(), any(Pageable.class));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
  }

  @Test
  void purgeKeepsAuditRowsInsideRetentionWindow() {
    // 364 days elapsed < 365-day retention: the query still runs (the
    // repository decides by created_at), but its cut-off predates every
    // row's creation, so nothing is selected and nothing is deleted.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(364));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts.auditLogRows()).isEqualTo(0);
    verify(auditLog, never()).deleteAllByIdInBatch(any());
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(auditLog).findPurgeableIds(cutoffCaptor.capture(), any(Pageable.class));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.minus(Duration.ofDays(1)));
  }

  @Test
  void purgeDeletesAuditRowsInBatches() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    List<Long> fullBatch = LongStream.range(0, DataRetentionService.BATCH_SIZE)
        .boxed().toList();
    List<Long> partialBatch = LongStream.range(3000, 3100).boxed().toList();
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(fullBatch, partialBatch);

    clock.advance(Duration.ofDays(366));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 600, 0, 0, 0));
    verify(auditLog, times(2)).deleteAllByIdInBatch(any());
    verify(auditLog).deleteAllByIdInBatch(fullBatch);
    verify(auditLog).deleteAllByIdInBatch(partialBatch);
  }

  @Test
  void purgeDeletesWebhookEventsPastRetentionWindow() {
    // The repository query selects by created_at alone; here it hands
    // back the two deliveries that predate the retention cut-off.
    // Deliveries inside the window fail the predicate, so they are
    // never handed back and never deleted.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    when(webhookEvents.findPurgeableIds(any(), any())).thenReturn(List.of(21L, 22L));

    clock.advance(Duration.ofDays(181));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 2, 0, 0));
    assertThat(counts.webhookEvents()).isEqualTo(2);
    verify(webhookEvents, times(1)).deleteAllByIdInBatch(List.of(21L, 22L));

    // now = EPOCH + 181 d; the 180-day default retention puts the
    // creation cut-off at EPOCH + 1 d.
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(webhookEvents, times(1)).findPurgeableIds(cutoffCaptor.capture(),
        any(Pageable.class));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
  }

  @Test
  void purgeKeepsWebhookEventsInsideRetentionWindow() {
    // 179 days elapsed < 180-day retention: the cut-off predates every
    // row's creation, so nothing is selected and nothing is deleted.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    when(webhookEvents.findPurgeableIds(any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(179));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts.webhookEvents()).isEqualTo(0);
    verify(webhookEvents, never()).deleteAllByIdInBatch(any());
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(webhookEvents).findPurgeableIds(cutoffCaptor.capture(), any(Pageable.class));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.minus(Duration.ofDays(1)));
  }

  @Test
  void purgeDeletesWebhookEventsInBatches() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    List<Long> fullBatch = LongStream.range(0, DataRetentionService.BATCH_SIZE)
        .boxed().toList();
    List<Long> partialBatch = LongStream.range(4000, 4100).boxed().toList();
    when(webhookEvents.findPurgeableIds(any(), any()))
        .thenReturn(fullBatch, partialBatch);

    clock.advance(Duration.ofDays(181));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 600, 0, 0));
    verify(webhookEvents, times(2)).deleteAllByIdInBatch(any());
    verify(webhookEvents).deleteAllByIdInBatch(fullBatch);
    verify(webhookEvents).deleteAllByIdInBatch(partialBatch);
  }

  @Test
  void purgeDeletesDeadEmailVerificationTokensPastRetention() {
    // The repository query is what decides "dead" (used, or expired as
    // of now); here it hands back the used row and the expired-never-used
    // row that both predate the retention cut-off.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    when(emailVerificationTokens.findPurgeableIds(any(), any(), any()))
        .thenReturn(List.of(31L, 32L));

    clock.advance(Duration.ofDays(31));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 0, 2, 0));
    assertThat(counts.emailVerificationTokens()).isEqualTo(2);
    verify(emailVerificationTokens, times(1)).deleteAllByIdInBatch(List.of(31L, 32L));

    // now = EPOCH + 31 d; the 30-day default retention puts the creation
    // cut-off at EPOCH + 1 d — anything created later is still evidence
    // and must survive.
    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(emailVerificationTokens, times(1))
        .findPurgeableIds(nowCaptor.capture(), cutoffCaptor.capture(),
            any(Pageable.class));
    assertThat(nowCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(31)));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
  }

  @Test
  void purgeNeverDeletesWhatTheVerificationQueryDidNotSelect() {
    // A still-valid unused token (an in-flight verification) and dead
    // rows still inside the retention window both fail the repository
    // predicate, so the query returns nothing and the service deletes
    // nothing — the service itself never widens the selection.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    when(emailVerificationTokens.findPurgeableIds(any(), any(), any()))
        .thenReturn(List.of());

    clock.advance(Duration.ofDays(365));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts.emailVerificationTokens()).isEqualTo(0);
    verify(emailVerificationTokens, never()).deleteAllByIdInBatch(any());
    ArgumentCaptor<Instant> nowCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(emailVerificationTokens)
        .findPurgeableIds(nowCaptor.capture(), any(), any(Pageable.class));
    assertThat(nowCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(365)));
  }

  @Test
  void purgeDeletesEmailVerificationTokensInBatches() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    List<Long> fullBatch = LongStream.range(0, DataRetentionService.BATCH_SIZE)
        .boxed().toList();
    List<Long> partialBatch = LongStream.range(5000, 5100).boxed().toList();
    when(emailVerificationTokens.findPurgeableIds(any(), any(), any()))
        .thenReturn(fullBatch, partialBatch);

    clock.advance(Duration.ofDays(31));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 0, 600, 0));
    verify(emailVerificationTokens, times(2)).deleteAllByIdInBatch(any());
    verify(emailVerificationTokens).deleteAllByIdInBatch(fullBatch);
    verify(emailVerificationTokens).deleteAllByIdInBatch(partialBatch);
  }

  @Test
  void purgeDeletesLoginEventsPastRetentionWindow() {
    // The repository query selects by created_at alone; here it hands
    // back the two rows that predate the retention cut-off. Rows inside
    // the window fail the predicate, so they are never handed back and
    // never deleted.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    when(loginEvents.findPurgeableIds(any(), any())).thenReturn(List.of(41L, 42L));

    clock.advance(Duration.ofDays(181));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 0, 0, 2));
    assertThat(counts.loginEvents()).isEqualTo(2);
    verify(loginEvents, times(1)).deleteAllByIdInBatch(List.of(41L, 42L));

    // now = EPOCH + 181 d; the 180-day default retention puts the
    // creation cut-off at EPOCH + 1 d.
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(loginEvents, times(1)).findPurgeableIds(cutoffCaptor.capture(),
        any(Pageable.class));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.plus(Duration.ofDays(1)));
  }

  @Test
  void purgeKeepsLoginEventsInsideRetentionWindow() {
    // 179 days elapsed < 180-day retention: the cut-off predates every
    // row's creation, so nothing is selected and nothing is deleted.
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    when(loginEvents.findPurgeableIds(any(), any())).thenReturn(List.of());

    clock.advance(Duration.ofDays(179));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts.loginEvents()).isEqualTo(0);
    verify(loginEvents, never()).deleteAllByIdInBatch(any());
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(loginEvents).findPurgeableIds(cutoffCaptor.capture(), any(Pageable.class));
    assertThat(cutoffCaptor.getValue()).isEqualTo(Instant.EPOCH.minus(Duration.ofDays(1)));
  }

  @Test
  void purgeDeletesLoginEventsInBatches() {
    when(refreshTokens.findStaleIds(any(), any(), any())).thenReturn(List.of());
    when(idempotencyKeys.findTerminalIdsBefore(any(), any(), any())).thenReturn(List.of());
    when(passwordResetTokens.findPurgeableIds(any(), any(), any())).thenReturn(List.of());
    when(auditLog.findPurgeableIds(any(), any())).thenReturn(List.of());
    List<Long> fullBatch = LongStream.range(0, DataRetentionService.BATCH_SIZE)
        .boxed().toList();
    List<Long> partialBatch = LongStream.range(6000, 6100).boxed().toList();
    when(loginEvents.findPurgeableIds(any(), any()))
        .thenReturn(fullBatch, partialBatch);

    clock.advance(Duration.ofDays(181));
    DataRetentionService.PurgeCounts counts = purge.purgeStaleData();

    assertThat(counts).isEqualTo(new DataRetentionService.PurgeCounts(0, 0, 0, 0, 0, 0, 600));
    verify(loginEvents, times(2)).deleteAllByIdInBatch(any());
    verify(loginEvents).deleteAllByIdInBatch(fullBatch);
    verify(loginEvents).deleteAllByIdInBatch(partialBatch);
  }
}
