package com.toni.marketplace.audit;

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
 * One append-only audit-trail row (backlog #98, Flyway V22): who did what
 * to which row, and when. Written by {@link AuditService} from inside the
 * audited transition's own transaction, so a transition that rolls back
 * leaves no row and a committed transition leaves exactly one.
 *
 * <p>Honest minimalism, by design:
 *
 * <ul>
 *   <li>No payload/details column — the row records the ids already in the
 *       database and nothing else, so the trail itself stores no new PII
 *       (no passwords, no TOTP material, no request bodies, ever).</li>
 *   <li>No foreign keys — an audit row must survive deletion of the user
 *       or order it describes; referential cleanup would defeat the
 *       trail's purpose.</li>
 *   <li>No setters — rows are immutable once written; the service exposes
 *       no update or delete path.</li>
 * </ul>
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The authenticated user who performed the transition. */
  @Column(name = "actor_user_id", nullable = false)
  private Long actorUserId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 32)
  private AuditAction action;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "target_type", nullable = false, length = 16)
  private AuditTargetType targetType;

  /** Id of the target row in its own table (see {@link AuditTargetType}). */
  @Column(name = "target_id", nullable = false)
  private Long targetId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditLog() {}

  public AuditLog(Long actorUserId, AuditAction action, AuditTargetType targetType,
                  Long targetId, Instant createdAt) {
    this.actorUserId = actorUserId;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.createdAt = createdAt;
  }

  public Long getId() { return id; }
  public Long getActorUserId() { return actorUserId; }
  public AuditAction getAction() { return action; }
  public AuditTargetType getTargetType() { return targetType; }
  public Long getTargetId() { return targetId; }
  public Instant getCreatedAt() { return createdAt; }
}
