package com.toni.marketplace.auth;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.common.InvalidCredentialsException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step-up grants for the account data export (backlog #188). Until
 * now the export (#172) — the heaviest sensitive read in the app —
 * required only a live access token, while every other sensitive
 * self-service action (password change, email change, account
 * closure, TOTP disable) demands a fresh proof. This service closes
 * that gap with a two-step shape:
 *
 * <ol>
 *   <li>{@link #confirm} verifies the fresh proof through
 *       {@link AuthService#requireStepUpProof} (current password, plus
 *       a TOTP/recovery code when 2FA is enabled — identical 401 for
 *       a wrong password and a wrong code, failed code proofs feed
 *       the shared #101 lockout) and mints a short-lived single-use
 *       grant: 32 random bytes, only its SHA-256 hash stored (the
 *       #90/#138 token discipline), TTL
 *       {@code app.auth.data-export.grant-ttl} (default 5 min). A
 *       fresh confirm retires the user's prior grants, so at most one
 *       live grant exists per account. A
 *       {@code DATA_EXPORT_GRANT_ISSUED} audit row joins the mint.</li>
 *   <li>{@link #consumeForExport} runs inside the export's own
 *       transaction: a missing, unknown, expired, already-used or
 *       other-account grant is the identical 401; a valid grant is
 *       stamped used, so the download it authorized can never be
 *       replayed with it.</li>
 * </ol>
 *
 * <p>The confirm orchestration is deliberately NOT one transaction:
 * the proof step carries {@code noRollbackFor} semantics (the #101
 * lockout counter increment must survive the 401 it produces), so it
 * commits on its own; the mint (delete prior + save + audit) then
 * runs in a short transaction of its own. A crash between the two
 * leaves the account with no grant — the user simply confirms again.
 */
@Service
public class DataExportGrantService {

  /** 32 random bytes per grant (256 bits — unguessable, hash-only storage). */
  private static final int GRANT_BYTES = 32;

  private final AuthService auth;
  private final DataExportGrantRepository grants;
  private final DataExportGrantProperties properties;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate mintTx;
  private final SecureRandom random = new SecureRandom();

  public DataExportGrantService(AuthService auth, DataExportGrantRepository grants,
                                DataExportGrantProperties properties, AuditService audit,
                                Clock clock, PlatformTransactionManager txManager) {
    this.auth = auth;
    this.grants = grants;
    this.properties = properties;
    this.audit = audit;
    this.clock = clock;
    this.mintTx = new TransactionTemplate(txManager);
  }

  /** A minted grant: the raw value (shown once) and its expiry. */
  public record IssuedGrant(String grant, Instant expiresAt) {}

  /**
   * Verifies the step-up proof and mints the caller's single live
   * grant. Proof failures propagate from
   * {@link AuthService#requireStepUpProof} as the identical 401 (or
   * 423 under an active TOTP lock) and mint nothing.
   */
  public IssuedGrant confirm(long userId, String currentPassword, String code) {
    auth.requireStepUpProof(userId, currentPassword, code);
    byte[] raw = new byte[GRANT_BYTES];
    random.nextBytes(raw);
    String grant = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant expiresAt = clock.instant().plus(properties.getGrantTtl());
    mintTx.executeWithoutResult(status -> {
      grants.deleteByUserId(userId);
      grants.save(new DataExportGrant(userId, JwtTokenService.sha256Hex(grant), expiresAt));
      audit.record(userId, AuditAction.DATA_EXPORT_GRANT_ISSUED,
          AuditTargetType.USER, userId);
    });
    return new IssuedGrant(grant, expiresAt);
  }

  /**
   * Consumes the grant presented for one export. Must be called
   * inside the export transaction, so a failed export build rolls the
   * consumption back with it (the grant survives for a retry) and a
   * successful one commits it. Every failure — missing, unknown,
   * expired, used, or minted for another account — is the identical
   * {@link InvalidCredentialsException} (401), so the response is
   * never an oracle for which check failed.
   */
  public void consumeForExport(long userId, String rawGrant) {
    if (rawGrant == null || rawGrant.isBlank()) {
      throw new InvalidCredentialsException();
    }
    DataExportGrant grant = grants.findByTokenHash(JwtTokenService.sha256Hex(rawGrant))
        .orElseThrow(InvalidCredentialsException::new);
    Instant now = clock.instant();
    if (!grant.getUserId().equals(userId) || grant.isUsed() || grant.isExpired(now)) {
      throw new InvalidCredentialsException();
    }
    grant.setUsedAt(now);
    grants.save(grant);
  }
}
