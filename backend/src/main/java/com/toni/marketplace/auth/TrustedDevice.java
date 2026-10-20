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
 * Server-side record of one trusted device for the TOTP challenge
 * (backlog #199, Flyway V54), identified by the SHA-256 of its current
 * token (the raw token is never stored — it travels only in the
 * {@code trusted_device} httpOnly cookie). The token rotates on every
 * trusted login: the outgoing hash moves to {@code previousTokenHash},
 * and presenting that rotated-out token afterwards deletes the row —
 * the refresh-token family's replay discipline (#39/#62) applied to
 * device trust: a replay means the cookie may have been copied, so
 * the device's trust dies and the next login pays the full challenge.
 */
@Entity
@Table(name = "trusted_device")
public class TrustedDevice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** SHA-256 hex of the current trusted-device token; unique. */
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  /** SHA-256 hex of the token this row rotated away from; NULL until the first rotation. */
  @Column(name = "previous_token_hash", length = 64)
  private String previousTokenHash;

  /** Device label captured at trust time / last trusted use (the request's User-Agent). */
  @Column(name = "user_agent", length = 512)
  private String userAgent;

  /** Remote address captured at trust time / last trusted use. */
  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TrustedDevice() {}

  public TrustedDevice(Long userId, String tokenHash, String userAgent, String ipAddress,
                       Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.userAgent = userAgent;
    this.ipAddress = ipAddress;
    this.expiresAt = expiresAt;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public boolean isExpired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getTokenHash() { return tokenHash; }
  public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
  public String getPreviousTokenHash() { return previousTokenHash; }
  public void setPreviousTokenHash(String previousTokenHash) {
    this.previousTokenHash = previousTokenHash;
  }
  public String getUserAgent() { return userAgent; }
  public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
  public String getIpAddress() { return ipAddress; }
  public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
  public Instant getLastUsedAt() { return lastUsedAt; }
  public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
  public Instant getCreatedAt() { return createdAt; }
}
