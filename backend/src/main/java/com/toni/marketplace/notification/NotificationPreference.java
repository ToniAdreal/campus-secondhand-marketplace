package com.toni.marketplace.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One user's explicit toggle for one notification type (backlog #216,
 * Flyway V62). A row exists only after the user toggles that type in
 * settings — an absent row means enabled, so existing users change
 * nothing. The {@code (user_id, type)} unique pair makes the settings
 * write an upsert and the recording seam's probe a single-row lookup.
 *
 * <p>The user reference is a plain id, mirroring {@link Notification}:
 * referential integrity comes from the V62 foreign key, not the ORM.
 */
@Entity
@Table(name = "notification_preference",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_notification_preference_user_type",
        columnNames = {"user_id", "type"}))
public class NotificationPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 32)
  private NotificationType type;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected NotificationPreference() {}

  public NotificationPreference(Long userId, NotificationType type, boolean enabled,
                                Instant updatedAt) {
    this.userId = userId;
    this.type = type;
    this.enabled = enabled;
    this.updatedAt = updatedAt;
  }

  /** Applies a fresh toggle from settings, stamping the change time. */
  public void setEnabled(boolean enabled, Instant when) {
    this.enabled = enabled;
    this.updatedAt = when;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public NotificationType getType() { return type; }
  public boolean isEnabled() { return enabled; }
  public Instant getUpdatedAt() { return updatedAt; }
}
