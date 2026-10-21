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
 * Server-side record of one issued email-change token (backlog #208),
 * identified by the SHA-256 of the token itself (the raw token is never
 * stored — it travels only through {@link EmailChangeMailSender}).
 * Single-use: confirming stamps {@code usedAt}, and a replayed token is
 * rejected with the identical 401 as an unknown or expired one. A fresh
 * change request deletes the user's prior unused rows, so at most one
 * live token exists per account (mirrors {@link EmailVerificationToken},
 * backlog #138).
 *
 * <p>The row carries its own {@code pendingEmail} copy: the confirm
 * switches the account to the address THIS token was issued for, never
 * to whatever a later request may have staged on the user row.
 */
@Entity
@Table(name = "email_change_token")
public class EmailChangeToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** The canonical new address this token confirms the switch to. */
  @Column(name = "pending_email", nullable = false, length = 255)
  private String pendingEmail;

  /** SHA-256 hex of the raw email-change token; unique. */
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** When the token was consumed by a successful confirm; NULL while unused. */
  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected EmailChangeToken() {}

  public EmailChangeToken(Long userId, String pendingEmail, String tokenHash,
                          Instant expiresAt) {
    this.userId = userId;
    this.pendingEmail = pendingEmail;
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
  public String getPendingEmail() { return pendingEmail; }
  public String getTokenHash() { return tokenHash; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getUsedAt() { return usedAt; }
  public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
