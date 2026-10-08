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
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
