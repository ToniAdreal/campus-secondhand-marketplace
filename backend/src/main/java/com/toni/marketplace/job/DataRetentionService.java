package com.toni.marketplace.job;

import com.toni.marketplace.auth.PasswordResetTokenRepository;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.IdempotencyStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   permanent archive (backlog #102).</li>
 * </ul>
 *
 * <p>All purges delete in batches of {@value #BATCH_SIZE} so no single
 * delete statement grows unbounded. {@code IN_PROGRESS} idempotency keys are
 * never touched — an owner may still be working — and a still-valid unused
 * password-reset token is never touched either: a purge must not kill an
 * in-flight reset.
 *
 * <p>Honest scope: the schedule fires in the JVM's default time zone (the
 * cron is fixed, not zone-pinned), and both purges run inside one
 * transaction — a failure mid-purge rolls back and retries the next night.
 * The {@code @Transactional} lives on the scheduled method itself, not on a
 * self-invoked helper, so the proxy actually applies it.
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
  private final CleanupProperties props;
  private final Clock clock;

  public DataRetentionService(RefreshTokenRepository refreshTokens,
                              IdempotencyKeyRepository idempotencyKeys,
                              PasswordResetTokenRepository passwordResetTokens,
                              CleanupProperties props,
                              Clock clock) {
    this.refreshTokens = refreshTokens;
    this.idempotencyKeys = idempotencyKeys;
    this.passwordResetTokens = passwordResetTokens;
    this.props = props;
    this.clock = clock;
  }

  /** How many rows of each kind one purge run removed. */
  public record PurgeCounts(int refreshTokens, int idempotencyKeys,
                            int passwordResetTokens) {}

  /**
   * Runs nightly at 03:00 server-local time. Also callable directly in tests
   * with a controllable {@link Clock}.
   */
  @Scheduled(cron = "0 0 3 * * *")
  @Transactional
  public PurgeCounts purgeStaleData() {
    Instant now = clock.instant();
    int tokens = purgeRefreshTokens(now);
    int keys = purgeIdempotencyKeys(now);
    int resets = purgePasswordResetTokens(now);
    log.info("stale-data purge removed {} refresh_token, {} idempotency_key "
        + "and {} password_reset_token rows", tokens, keys, resets);
    return new PurgeCounts(tokens, keys, resets);
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
}
