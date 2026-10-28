package com.toni.marketplace.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Server-side record of one issued WebAuthn challenge (backlog #220),
 * following the token-table discipline of the password-reset (#90) and
 * email-verification (#138) flows: only the SHA-256 hex of the raw
 * challenge bytes is stored (the raw bytes travel to the browser inside
 * the ceremony options and never touch the database), the row is
 * single-use ({@code usedAt} is stamped on the first verify attempt —
 * success or failure — so a challenge can never be verified twice), and
 * at most one live row exists per (user, purpose): issuing new options
 * deletes the user's prior unused row for that purpose first.
 *
 * <p>The nightly stale-data purge (backlog #262,
 * {@code DataRetentionService}) sweeps used-or-expired rows past the
 * retention window; the one-live-row-per-purpose discipline still
 * bounds the live set — rows are created only on an explicit options
 * call and superseded by the next one — but the purge is what removes
 * the expired rows of accounts that never call options again. A
 * still-valid unused row is never purged.
 */
@Entity
@Table(name = "passkey_challenge")
public class PasskeyChallenge {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 16)
  private PasskeyChallengePurpose purpose;

  /** SHA-256 hex of the raw challenge bytes; unique. */
  @Column(name = "challenge_hash", nullable = false, unique = true, length = 64)
  private String challengeHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** When the challenge was consumed by a verify attempt; NULL while unused. */
  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PasskeyChallenge() {}

  public PasskeyChallenge(Long userId, PasskeyChallengePurpose purpose, String challengeHash,
                          Instant expiresAt) {
    this.userId = userId;
    this.purpose = purpose;
    this.challengeHash = challengeHash;
    this.expiresAt = expiresAt;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public boolean isUsed() {
    return usedAt != null;
  }

  public boolean isExpired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public PasskeyChallengePurpose getPurpose() { return purpose; }
  public String getChallengeHash() { return challengeHash; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getUsedAt() { return usedAt; }
  public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
