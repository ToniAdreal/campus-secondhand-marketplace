package com.toni.marketplace.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Registered user. Passwords are stored as BCrypt hashes only — never plaintext. */
@Entity
@Table(name = "app_user")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 60)
  private String username;

  @Column(nullable = false, unique = true, length = 255)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Convert(converter = RolesConverter.class)
  @Column(nullable = false, length = 255)
  private Set<Role> roles = EnumSet.of(Role.USER);

  /**
   * Bearer-token kill switch. Every access token carries this version in its
   * {@code tver} claim (see JwtTokenService); JwtAuthenticationFilter rejects
   * tokens whose claim disagrees with the row. AuthService bumps it on
   * password change, so pre-change tokens 401 immediately instead of living
   * out their 15-minute TTL. Starts at 0 for all rows (V10 backfills).
   */
  @Column(name = "token_version", nullable = false)
  private long tokenVersion;

  /**
   * Per-account login lockout (backlog #60). Consecutive failed logins since
   * the last success; a success resets it to 0. When it reaches
   * {@code app.auth.login-lockout.max-attempts}, {@link #lockedUntil} is set
   * {@code lock-duration} into the future and the account rejects logins
   * with 423 until then. An expired {@code lockedUntil} resets the counter
   * on the next attempt — the account gets a fresh allowance.
   */
  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  /**
   * TOTP two-factor (backlog #78). RFC 6238 shared secret (160 bits),
   * set by POST /api/auth/2fa/setup; null until then. {@code totpEnabled}
   * flips on only after POST /api/auth/2fa/enable verifies a code against
   * the secret.
   *
   * <p>Encryption at rest (backlog #89): the column holds the {@link
   * TotpSecretCipher} {@code v1:} form (AES-256-GCM, random IV per row) —
   * never the plaintext Base32 secret. Rows written before #89 may still
   * hold plaintext Base32; {@code AuthService} resolves both forms and
   * re-encrypts legacy rows on the next enable/authenticate. The column
   * was widened 64 → 255 by Flyway V19 because the {@code v1:} form of a
   * 32-char secret is 84 chars.
   */
  @Column(name = "totp_secret", length = 255)
  private String totpSecret;

  @Column(name = "totp_enabled", nullable = false)
  private boolean totpEnabled;

  /**
   * ADMIN disable flag (backlog #88, Flyway V18). A disabled account cannot
   * log in, its Bearer tokens are rejected by {@link JwtAuthenticationFilter},
   * and disabling revokes all of its refresh-token families. Starts false
   * for every row (V18 backfills).
   */
  @Column(name = "disabled", nullable = false)
  private boolean disabled;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected User() {}

  public User(String username, String email, String passwordHash) {
    this.username = username;
    this.email = email;
    this.passwordHash = passwordHash;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public Long getId() { return id; }
  public String getUsername() { return username; }
  public String getEmail() { return email; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public Set<Role> getRoles() { return roles; }
  public void setRoles(Set<Role> roles) { this.roles = roles; }
  public long getTokenVersion() { return tokenVersion; }
  public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }
  public int getFailedLoginAttempts() { return failedLoginAttempts; }
  public void setFailedLoginAttempts(int failedLoginAttempts) {
    this.failedLoginAttempts = failedLoginAttempts;
  }
  public Instant getLockedUntil() { return lockedUntil; }
  public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
  public String getTotpSecret() { return totpSecret; }
  public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
  public boolean isTotpEnabled() { return totpEnabled; }
  public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
  public boolean isDisabled() { return disabled; }
  public void setDisabled(boolean disabled) { this.disabled = disabled; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
