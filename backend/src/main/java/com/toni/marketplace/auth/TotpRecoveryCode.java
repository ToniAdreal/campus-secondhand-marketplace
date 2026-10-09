package com.toni.marketplace.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Server-side record of one issued TOTP recovery code (backlog #91),
 * identified by the SHA-256 of the normalized code — the raw code is
 * shown exactly once, in the {@code POST /api/auth/2fa/enable} response,
 * and never stored. Single-use: a successful
 * {@code POST /api/auth/2fa/authenticate} with the code stamps
 * {@code usedAt}; a replay is rejected with the identical 401 as a wrong
 * TOTP code. Re-enrollment (setup + enable again) deletes all of the
 * user's rows and issues a fresh set, so codes from a previous
 * enrollment can never authenticate.
 */
@Entity
@Table(name = "totp_recovery_code")
public class TotpRecoveryCode {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** SHA-256 hex of the normalized recovery code; unique. */
  @Column(name = "code_hash", nullable = false, unique = true, length = 64)
  private String codeHash;

  /** When the code was consumed by a successful authenticate; NULL while unused. */
  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TotpRecoveryCode() {}

  public TotpRecoveryCode(Long userId, String codeHash) {
    this.userId = userId;
    this.codeHash = codeHash;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public boolean isUsed() {
    return usedAt != null;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getCodeHash() { return codeHash; }
  public Instant getUsedAt() { return usedAt; }
  public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
