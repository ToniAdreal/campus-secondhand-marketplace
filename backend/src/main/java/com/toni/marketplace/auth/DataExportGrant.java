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
 * Server-side record of one issued data-export grant (backlog #188),
 * identified by the SHA-256 of the grant itself (the raw grant is
 * never stored — it travels only in the confirm response and in the
 * {@code X-Export-Grant} header of the one download it authorizes).
 * Single-use: a successful export stamps {@code usedAt}, and a
 * replayed grant is rejected with the identical 401 as an unknown or
 * expired one. A fresh confirm deletes the user's prior rows, so at
 * most one live grant exists per account (mirroring the #90
 * password-reset discipline).
 */
@Entity
@Table(name = "data_export_grant")
public class DataExportGrant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** SHA-256 hex of the raw grant; unique. */
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** When the grant was consumed by the export it authorized; NULL while unused. */
  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected DataExportGrant() {}

  public DataExportGrant(Long userId, String tokenHash, Instant expiresAt) {
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
