package com.toni.marketplace.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recorded login outcome (backlog #171, Flyway V39), appended by
 * {@link LoginHistoryRecorder} in its own {@code REQUIRES_NEW}
 * transaction so the row survives the response its attempt produced.
 * Rows are append-only; the only deletion is the retention purge
 * ({@code DataRetentionService}), by {@code created_at} alone.
 *
 * <p>{@code userId} is a plain nullable id with no JPA association and no
 * database foreign key (see the V39 header): unknown-identifier attempts
 * have no account ({@code null}), and a tombstoned account must not take
 * its sign-in evidence with it. {@code identifier} is the attempted
 * identifier exactly as typed; second-factor rows carry the account's
 * username instead (the challenge holds no typed identifier).
 */
@Entity
@Table(name = "login_event")
public class LoginEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The account the attempt resolved to; NULL for unknown identifiers. */
  @Column(name = "user_id")
  private Long userId;

  /** Attempted identifier as typed (320 = the maximum email length). */
  @Column(nullable = false, length = 320)
  private String identifier;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 32)
  private LoginOutcome outcome;

  /** Client IP resolved at capture time (see {@link SessionMeta}); NULL when unknown. */
  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  /** User-Agent captured at capture time; NULL when the client sent none. */
  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LoginEvent() {}

  public LoginEvent(Long userId, String identifier, LoginOutcome outcome,
                    String ipAddress, String userAgent, Instant createdAt) {
    this.userId = userId;
    this.identifier = identifier;
    this.outcome = outcome;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.createdAt = createdAt;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getIdentifier() { return identifier; }
  public LoginOutcome getOutcome() { return outcome; }
  public String getIpAddress() { return ipAddress; }
  public String getUserAgent() { return userAgent; }
  public Instant getCreatedAt() { return createdAt; }
}
