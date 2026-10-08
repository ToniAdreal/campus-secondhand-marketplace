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
 * Server-side record of one issued refresh token, identified by the SHA-256 of
 * the token itself (the raw token is never stored). Tokens are single-use:
 * rotation revokes the consumed row; logout deletes the family rows.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** SHA-256 hex of the compact refresh token; unique. */
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  /** The JWT "jti" claim; links a rotation chain (replacedBy). */
  @Column(nullable = false, length = 36)
  private String jti;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(nullable = false)
  private boolean revoked;

  /**
   * When the row was revoked by rotation. NULL for never-revoked rows and
   * for rows revoked before the V8 migration; the rotation grace window
   * (backlog #39) is measured from here.
   */
  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "replaced_by", length = 36)
  private String replacedBy;

  /**
   * Instant of the original login that founded this token's family, carried
   * onto every rotated row. JwtTokenService refuses a refresh past
   * {@code app.jwt.refresh-max-age} from here (absolute family lifetime,
   * backlog #61) — even when the presented token is unexpired.
   */
  @Column(name = "family_issued_at", nullable = false)
  private Instant familyIssuedAt;

  /**
   * The jti of the family's root token (the login-time token). Lets the
   * family-cap revocation delete only this session's chain instead of every
   * session on the account (backlog #62 builds per-session management on
   * this identifier).
   */
  @Column(name = "family_jti", nullable = false, length = 36)
  private String familyJti;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RefreshToken() {}

  public RefreshToken(String tokenHash, String jti, Long userId, Instant expiresAt,
                      Instant familyIssuedAt, String familyJti) {
    this.tokenHash = tokenHash;
    this.jti = jti;
    this.userId = userId;
    this.expiresAt = expiresAt;
    this.familyIssuedAt = familyIssuedAt;
    this.familyJti = familyJti;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public boolean isExpired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  public Long getId() { return id; }
  public String getTokenHash() { return tokenHash; }
  public String getJti() { return jti; }
  public Long getUserId() { return userId; }
  public Instant getExpiresAt() { return expiresAt; }
  public boolean isRevoked() { return revoked; }
  public void setRevoked(boolean revoked) { this.revoked = revoked; }
  public Instant getRevokedAt() { return revokedAt; }
  public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
  public String getReplacedBy() { return replacedBy; }
  public void setReplacedBy(String replacedBy) { this.replacedBy = replacedBy; }
  public Instant getFamilyIssuedAt() { return familyIssuedAt; }
  public String getFamilyJti() { return familyJti; }
  public Instant getCreatedAt() { return createdAt; }
}
