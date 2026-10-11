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
 * One outgoing password hash of one user (backlog #139, Flyway V27).
 * Appended by {@link PasswordHistoryService#recordOutgoing} inside the
 * same transaction that replaces the password, so a rolled-back change
 * or reset leaves no row. Only the BCrypt hash is stored — never a raw
 * password. Rows are append-only; the reuse check reads the newest
 * {@code app.auth.password-history-size} rows by id.
 */
@Entity
@Table(name = "password_history")
public class PasswordHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  /** BCrypt hash of the password this row's owner moved away from. */
  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PasswordHistory() {}

  public PasswordHistory(Long userId, String passwordHash) {
    this.userId = userId;
    this.passwordHash = passwordHash;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getPasswordHash() { return passwordHash; }
  public Instant getCreatedAt() { return createdAt; }
}
