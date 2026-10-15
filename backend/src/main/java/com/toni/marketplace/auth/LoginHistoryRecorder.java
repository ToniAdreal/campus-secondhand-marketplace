package com.toni.marketplace.auth;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends {@code login_event} rows (backlog #171), split out of
 * {@link AuthService} on purpose — the {@code WebhookEventRecorder}
 * pattern from the order package (backlog #161).
 *
 * <p>The {@code REQUIRES_NEW} transaction is load-bearing, not cosmetic:
 * {@code AuthService.login} is {@code @Transactional} WITHOUT
 * {@code noRollbackFor}, so a failed login's transaction rolls back —
 * and a history row written inside it would roll back with it, erasing
 * exactly the failed attempts the history exists to show. Recording here
 * commits the row in its own transaction before the caller's rollback,
 * so every outcome (401, 403 and 423 included) leaves its row. The
 * second-factor exchange ({@code authenticateTotp}) commits its failure
 * counters via {@code noRollbackFor} and does not strictly need the
 * split, but both stages record through this one bean so the discipline
 * cannot drift between them.
 *
 * <p>Strict on purpose, like {@code WebhookEventRecorder}: a recording
 * failure propagates instead of being swallowed — a login history that
 * silently drops rows is worse than a loud failure, and the test suite
 * pins the behaviour.
 */
@Service
public class LoginHistoryRecorder {

  private final LoginEventRepository events;

  public LoginHistoryRecorder(LoginEventRepository events) {
    this.events = events;
  }

  /**
   * Records one attempt's outcome.
   *
   * @param userIdOrNull the account the attempt resolved to, or NULL for an unknown identifier
   * @param identifier   the attempted identifier as typed (the account's username for second-factor rows)
   * @param outcome      the attempt's verdict
   * @param meta         device info captured from the request (IP + User-Agent)
   * @param at           when the attempt happened, from the caller's injected clock
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LoginEvent record(Long userIdOrNull, String identifier, LoginOutcome outcome,
                           SessionMeta meta, Instant at) {
    SessionMeta safeMeta = meta != null ? meta : SessionMeta.unknown();
    return events.saveAndFlush(new LoginEvent(userIdOrNull, identifier, outcome,
        safeMeta.ipAddress(), safeMeta.userAgent(), at));
  }
}
