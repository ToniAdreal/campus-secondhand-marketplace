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
 * Server-side record of one issued password-reset token, identified by the
 * SHA-256 of the token itself (the raw token is never stored — it travels
 * only through {@link PasswordResetMailSender}). Single-use: confirming a
 * reset stamps {@code usedAt}, and a replayed token is rejected with the
 * identical 401 as an unknown or expired one. A new reset request deletes
 * the user's prior unused rows, so at most one live token exists per
 * account (backlog #90).
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** SHA-256 hex of the raw reset token; unique. */
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** When the token was consumed by a successful confirm; NULL while unused. */
  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PasswordResetToken() {}

  public PasswordResetToken(Long userId, String tokenHash, Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
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
  public String getTokenHash() { return tokenHash; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getUsedAt() { return usedAt; }
  public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
