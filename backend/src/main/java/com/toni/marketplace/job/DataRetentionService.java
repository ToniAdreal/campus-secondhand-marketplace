package com.toni.marketplace.job;

import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.auth.DataExportGrantRepository;
import com.toni.marketplace.auth.EmailChangeTokenRepository;
import com.toni.marketplace.auth.EmailVerificationTokenRepository;
import com.toni.marketplace.auth.LoginEventRepository;
import com.toni.marketplace.auth.PasskeyChallengeRepository;
import com.toni.marketplace.auth.PasswordResetTokenRepository;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.TotpRecoveryCodeRepository;
import com.toni.marketplace.item.RecentlyViewedRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.IdempotencyStatus;
import com.toni.marketplace.order.WebhookEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly maintenance job that purges stale bookkeeping rows so they do not
 * accumulate forever:
 *
 * <ul>
 *   <li>revoked-or-expired {@code refresh_token} rows — rotation, logout and
 *   theft detection already kill these families; keeping them is dead weight
 *   past the retention window;</li>
 *   <li>terminal ({@code COMPLETED}/{@code FAILED}) {@code idempotency_key}
 *   rows — once the retention window has lapsed their purpose (replaying a
 *   stored order for a late client retry, or telling the client to use a new
 *   key after a failure) is spent, and the key becomes reusable as a fresh
 *   attempt;</li>
 *   <li>used-or-expired {@code password_reset_token} rows — a new request or
 *   a confirm only ever deletes the user's <em>unused</em> rows, so used rows
 *   and expired-never-used rows of users who never re-request would otherwise
 *   accumulate forever. They are brief security-audit evidence, not a
 *   permanent archive (backlog #102);</li>
 *   <li>{@code audit_log} rows past the retention window (backlog #118) —
 *   the trail is the security record, so its window (365 days by default)
 *   is materially longer than the token windows above;</li>
 *   <li>{@code webhook_event} rows past the retention window (backlog
 *   #152) — every accepted webhook delivery since #140 is recorded for
 *   PSP reconciliation, but the evidence value of a delivery decays, so
 *   rows age out by {@code created_at} only (180 days by default), never
 *   by outcome: the purge must not become a way to erase one specific
 *   delivery;</li>
 *   <li>used-or-expired {@code email_verification_token} rows (backlog
 *   #152) — the sibling of the password-reset purge: a confirm consumes a
 *   token or it expires unused, and neither is ever deleted by the
 *   verification flow itself, so dead rows would otherwise accumulate
 *   forever. Kept 30 days after creation by default, aligned with the
 *   password-reset window;</li>
 *   <li>{@code login_event} rows past the retention window (backlog
 *   #171) — every recorded login outcome since #171 is sign-in evidence
 *   for the account holder's history view, but its value decays, so
 *   rows age out by {@code created_at} only (180 days by default),
 *   never by outcome or user: the purge must not become a way to erase
 *   one specific attempt;</li>
 *   <li>{@code recently_viewed} trail rows past the retention window
 *   (backlog #195) — the browsing trail written by detail reads is a
 *   convenience surface, not a record: rows age out by
 *   {@code viewed_at} only (30 days by default), never by user or
 *   listing, and a repeat view refreshes its row, so an actively
 *   revisited listing never ages out from under its viewer;</li>
 *   <li>read {@code notification} rows past the retention window
 *   (backlog #259) — the inbox is a derived UI surface, not a
 *   record: rows age out by {@code read_at} only (90 days by
 *   default), never by recipient or type, and an unread row
 *   (null marker) is never selected, so an unopened notification
 *   survives indefinitely until its recipient reads it;</li>
 *   <li>stale auth bookkeeping rows (backlog #262) — the four kinds
 *   the earlier purges never touched, each kept 30 days after creation
 *   by default: used {@code totp_recovery_code} rows (unused codes are
 *   live backup credentials with no expiry, so only the used marker
 *   selects a row); used-or-expired {@code passkey_challenge} rows
 *   (the per-purpose options-path replacement only retires rows for
 *   that user and purpose, so expired rows of accounts that never call
 *   options again would otherwise linger); used-or-expired
 *   {@code email_change_token} rows (confirm stamps {@code usedAt} but
 *   keeps the row, and request/confirm only retire unused rows of the
 *   same user); and used-or-expired {@code data_export_grant} rows
 *   (a fresh confirm retires every prior grant, so only the account's
 *   latest grant can linger, once spent or expired). In every case a
 *   still-valid unused row is never selected: a purge must not kill an
 *   in-flight ceremony, email change or export download.</li>
 * </ul>
 *
 * <p>All purges delete in batches of {@value #BATCH_SIZE} so no single
 * delete statement grows unbounded. {@code IN_PROGRESS} idempotency keys are
 * never touched — an owner may still be working — and a still-valid unused
 * password-reset token is never touched either: a purge must not kill an
 * in-flight reset — and the same holds for verification: a still-valid
 * unused email-verification token is never touched either.
 *
 * <p>"Append-only" vs retention: the audit trail is append-only
 * <em>within</em> its retention window — no code path updates or deletes a
 * row inside it, and the purge deletes by {@code created_at} only, never
 * by action or actor, so no specific event can be selectively erased.
 * Operators who must keep evidence longer export the trail before the
 * window lapses; that export is the documented escape hatch, not a reason
 * to grow the table forever.
 *
 * <p>Honest scope: the schedule fires in the JVM's default time zone (the
 * cron is fixed, not zone-pinned), and all purges run inside one
 * transaction — a failure mid-purge rolls back and retries the next night.
 * The {@code @Transactional} lives on the scheduled method itself, not on a
 * self-invoked helper, so the proxy actually applies it.
 *
 * <p>Each firing first takes this job's single-instance lease (backlog
 * #215, {@link JobLockGuard}): the lease is acquired in its own
 * transaction before the purge's work and released only after the purge
 * transaction completes, so a second instance skips with zero counts
 * instead of double-purging.
 */
@Service
public class DataRetentionService {

  private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

  /** Rows deleted per repository call; keeps each delete statement small. */
  static final int BATCH_SIZE = 500;

  private static final Set<IdempotencyStatus> TERMINAL =
      EnumSet.of(IdempotencyStatus.COMPLETED, IdempotencyStatus.FAILED);

  private final RefreshTokenRepository refreshTokens;
  private final IdempotencyKeyRepository idempotencyKeys;
  private final PasswordResetTokenRepository passwordResetTokens;
  private final AuditLogRepository auditLog;
  private final WebhookEventRepository webhookEvents;
  private final EmailVerificationTokenRepository emailVerificationTokens;
  private final LoginEventRepository loginEvents;
  /** Single-instance lease name for this job (backlog #215). */
  public static final String LOCK_NAME = "data-retention";

  private final RecentlyViewedRepository recentlyViewed;
  private final NotificationRepository notifications;
  private final TotpRecoveryCodeRepository totpRecoveryCodes;
  private final PasskeyChallengeRepository passkeyChallenges;
  private final EmailChangeTokenRepository emailChangeTokens;
  private final DataExportGrantRepository dataExportGrants;
  private final CleanupProperties props;
  private final Clock clock;
  private final JobLockGuard jobLock;

  @Autowired
  public DataRetentionService(RefreshTokenRepository refreshTokens,
                              IdempotencyKeyRepository idempotencyKeys,
                              PasswordResetTokenRepository passwordResetTokens,
                              AuditLogRepository auditLog,
                              WebhookEventRepository webhookEvents,
                              EmailVerificationTokenRepository emailVerificationTokens,
                              LoginEventRepository loginEvents,
                              RecentlyViewedRepository recentlyViewed,
                              NotificationRepository notifications,
                              TotpRecoveryCodeRepository totpRecoveryCodes,
                              PasskeyChallengeRepository passkeyChallenges,
                              EmailChangeTokenRepository emailChangeTokens,
                              DataExportGrantRepository dataExportGrants,
                              CleanupProperties props,
                              Clock clock,
                              JobLockGuard jobLock) {
    this.refreshTokens = refreshTokens;
    this.idempotencyKeys = idempotencyKeys;
    this.passwordResetTokens = passwordResetTokens;
    this.auditLog = auditLog;
    this.webhookEvents = webhookEvents;
    this.emailVerificationTokens = emailVerificationTokens;
    this.loginEvents = loginEvents;
    this.recentlyViewed = recentlyViewed;
    this.notifications = notifications;
    this.totpRecoveryCodes = totpRecoveryCodes;
    this.passkeyChallenges = passkeyChallenges;
    this.emailChangeTokens = emailChangeTokens;
    this.dataExportGrants = dataExportGrants;
    this.props = props;
    this.clock = clock;
    this.jobLock = jobLock;
  }

  /** Direct-construction constructor for body-logic unit tests (no lease). */
  public DataRetentionService(RefreshTokenRepository refreshTokens,
                              IdempotencyKeyRepository idempotencyKeys,
                              PasswordResetTokenRepository passwordResetTokens,
                              AuditLogRepository auditLog,
                              WebhookEventRepository webhookEvents,
                              EmailVerificationTokenRepository emailVerificationTokens,
                              LoginEventRepository loginEvents,
                              RecentlyViewedRepository recentlyViewed,
                              NotificationRepository notifications,
                              TotpRecoveryCodeRepository totpRecoveryCodes,
                              PasskeyChallengeRepository passkeyChallenges,
                              EmailChangeTokenRepository emailChangeTokens,
                              DataExportGrantRepository dataExportGrants,
                              CleanupProperties props,
                              Clock clock) {
    this(refreshTokens, idempotencyKeys, passwordResetTokens, auditLog,
        webhookEvents, emailVerificationTokens, loginEvents, recentlyViewed,
        notifications, totpRecoveryCodes, passkeyChallenges, emailChangeTokens,
        dataExportGrants, props, clock, JobLockGuard.disabled());
  }

  /** How many rows of each kind one purge run removed. */
  public record PurgeCounts(int refreshTokens, int idempotencyKeys,
                            int passwordResetTokens, int auditLogRows,
                            int webhookEvents, int emailVerificationTokens,
                            int loginEvents, int recentlyViewedRows,
                            int notificationRows, int totpRecoveryCodes,
                            int passkeyChallenges, int emailChangeTokens,
                            int dataExportGrants) {}

  /**
   * Runs nightly at 03:00 server-local time. Also callable directly in tests
   * with a controllable {@link Clock}.
   */
  @Scheduled(cron = "0 0 3 * * *")
  @Transactional
  public PurgeCounts purgeStaleData() {
    if (!jobLock.tryAcquire(LOCK_NAME)) {
      return new PurgeCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    try {
      return purgeStaleDataLocked();
    } finally {
      // Inside this transaction the guard defers the actual release
      // until the transaction completes (see JobLockGuard.release).
      jobLock.release(LOCK_NAME);
    }
  }

  private PurgeCounts purgeStaleDataLocked() {
    Instant now = clock.instant();
    int tokens = purgeRefreshTokens(now);
    int keys = purgeIdempotencyKeys(now);
    int resets = purgePasswordResetTokens(now);
    int audit = purgeAuditLog(now);
    int webhooks = purgeWebhookEvents(now);
    int verifications = purgeEmailVerificationTokens(now);
    int loginEventsCount = purgeLoginEvents(now);
    int trail = purgeRecentlyViewed(now);
    int readNotifications = purgeNotifications(now);
    int usedRecoveryCodes = purgeTotpRecoveryCodes(now);
    int deadChallenges = purgePasskeyChallenges(now);
    int deadEmailChanges = purgeEmailChangeTokens(now);
    int deadExportGrants = purgeDataExportGrants(now);
    log.info("stale-data purge removed {} refresh_token, {} idempotency_key, "
        + "{} password_reset_token, {} audit_log, {} webhook_event, "
        + "{} email_verification_token, {} login_event, "
        + "{} recently_viewed, {} notification, {} totp_recovery_code, "
        + "{} passkey_challenge, {} email_change_token and "
        + "{} data_export_grant rows",
        tokens, keys, resets, audit, webhooks, verifications, loginEventsCount,
        trail, readNotifications, usedRecoveryCodes, deadChallenges,
        deadEmailChanges, deadExportGrants);
    return new PurgeCounts(tokens, keys, resets, audit, webhooks, verifications,
        loginEventsCount, trail, readNotifications, usedRecoveryCodes,
        deadChallenges, deadEmailChanges, deadExportGrants);
  }

  private int purgeNotifications(Instant now) {
    // Read notifications are judged by their read marker alone:
    // read_at past the retention window. The repository query's
    // non-null predicate is what guarantees an unread row — an
    // unopened notification — is never selected, regardless of age.
    Instant readBefore = now.minus(Duration.ofDays(props.getNotificationsRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = notifications.findPurgeableIds(readBefore, PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        notifications.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeRefreshTokens(Instant now) {
    // A revoked token is authentication-dead; an expired token can no longer
    // refresh. Both are kept until the retention window after their creation
    // for audit, then deleted.
    Instant createdBefore = now.minus(Duration.ofDays(props.getRefreshTokenRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      // Always re-read page 0: rows are deleted between pages, so a
      // forward-moving page index would skip rows.
      ids = refreshTokens.findStaleIds(now, createdBefore, PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        refreshTokens.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeIdempotencyKeys(Instant now) {
    // Terminal rows are judged by their last transition (updated_at): a key
    // that failed an hour ago still serves its "retry with a new key"
    // purpose, one that completed months ago does not.
    Instant updatedBefore = now.minus(Duration.ofDays(props.getIdempotencyRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = idempotencyKeys.findTerminalIdsBefore(TERMINAL, updatedBefore,
          PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        idempotencyKeys.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgePasswordResetTokens(Instant now) {
    // A reset row is purgeable only once it is dead (used, or expired as of
    // now) AND past the retention window after its creation. Reset tokens
    // live 30 minutes by default, so creation-vs-use skew is negligible and
    // the createdAt cut-off matches the refresh-token purge. The repository
    // query's dead-row predicate is what guarantees a still-valid unused
    // token — an in-flight reset — is never selected.
    Instant createdBefore = now.minus(Duration.ofDays(props.getPasswordResetRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = passwordResetTokens.findPurgeableIds(now, createdBefore,
          PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        passwordResetTokens.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeWebhookEvents(Instant now) {
    // Webhook deliveries are judged by age alone: created_at past the
    // retention window. There is deliberately no outcome predicate —
    // MATCHED and MISMATCH rows age out together, so the purge can never
    // erase one specific delivery (see the class javadoc).
    Instant createdBefore = now.minus(Duration.ofDays(props.getWebhookEventRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = webhookEvents.findPurgeableIds(createdBefore, PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        webhookEvents.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeLoginEvents(Instant now) {
    // Login history is judged by age alone: created_at past the retention
    // window. There is deliberately no outcome or user predicate —
    // SUCCESS and BAD_PASSWORD rows age out together, so the purge can
    // never erase one specific attempt (see the class javadoc).
    Instant createdBefore = now.minus(Duration.ofDays(props.getLoginEventRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = loginEvents.findPurgeableIds(createdBefore, PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        loginEvents.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeEmailVerificationTokens(Instant now) {
    // Mirror of the password-reset purge: a verification row is purgeable
    // only once it is dead (used, or expired as of now) AND past the
    // retention window after its creation. Verification tokens live 24 h
    // by default, so the 30-day window is far past any live token's
    // expiry anyway; the repository query's dead-row predicate is what
    // guarantees a still-valid unused token — an in-flight verification —
    // is never selected.
    Instant createdBefore =
        now.minus(Duration.ofDays(props.getEmailVerificationTokenRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = emailVerificationTokens.findPurgeableIds(now, createdBefore,
          PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        emailVerificationTokens.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeRecentlyViewed(Instant now) {
    // The trail is judged by its last view alone: viewed_at past the
    // retention window. There is deliberately no user or listing
    // predicate — one person's trail ages out on the same terms as
    // everyone's (see the class javadoc).
    Instant viewedBefore = now.minus(Duration.ofDays(props.getRecentlyViewedRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = recentlyViewed.findPurgeableIds(viewedBefore, PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        recentlyViewed.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeTotpRecoveryCodes(Instant now) {
    // Recovery codes carry no expiry, so the used marker alone decides
    // death: an unused row is a live backup credential and is never
    // selected, regardless of age. The retention window runs from
    // creation, mirroring the password-reset purge.
    Instant createdBefore =
        now.minus(Duration.ofDays(props.getTotpRecoveryCodeRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = totpRecoveryCodes.findPurgeableIds(createdBefore, PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        totpRecoveryCodes.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgePasskeyChallenges(Instant now) {
    // Mirror of the password-reset purge: a challenge row is purgeable
    // only once it is dead (used, or expired as of now) AND past the
    // retention window after its creation. The repository query's
    // dead-row predicate is what guarantees a still-valid unused
    // challenge — an in-flight ceremony — is never selected.
    Instant createdBefore =
        now.minus(Duration.ofDays(props.getPasskeyChallengeRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = passkeyChallenges.findPurgeableIds(now, createdBefore,
          PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        passkeyChallenges.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeEmailChangeTokens(Instant now) {
    // Mirror of the password-reset purge: a token row is purgeable only
    // once it is dead (used, or expired as of now) AND past the
    // retention window after its creation. The repository query's
    // dead-row predicate is what guarantees a still-valid unused token
    // — an in-flight email change — is never selected.
    Instant createdBefore =
        now.minus(Duration.ofDays(props.getEmailChangeTokenRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = emailChangeTokens.findPurgeableIds(now, createdBefore,
          PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        emailChangeTokens.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeDataExportGrants(Instant now) {
    // Mirror of the password-reset purge: a grant row is purgeable only
    // once it is dead (used, or expired as of now) AND past the
    // retention window after its creation. The repository query's
    // dead-row predicate is what guarantees a still-valid unused grant
    // — an in-flight export download — is never selected.
    Instant createdBefore =
        now.minus(Duration.ofDays(props.getDataExportGrantRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = dataExportGrants.findPurgeableIds(now, createdBefore,
          PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        dataExportGrants.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }

  private int purgeAuditLog(Instant now) {
    // The audit trail is judged by age alone: created_at past the
    // retention window. There is deliberately no action/actor predicate —
    // the purge must never become a way to erase one specific event while
    // keeping its neighbours (see the class javadoc on "append-only").
    Instant createdBefore = now.minus(Duration.ofDays(props.getAuditLogRetention()));
    int deleted = 0;
    List<Long> ids;
    do {
      ids = auditLog.findPurgeableIds(createdBefore, PageRequest.of(0, BATCH_SIZE));
      if (!ids.isEmpty()) {
        auditLog.deleteAllByIdInBatch(ids);
        deleted += ids.size();
      }
    } while (ids.size() == BATCH_SIZE);
    return deleted;
  }
}
