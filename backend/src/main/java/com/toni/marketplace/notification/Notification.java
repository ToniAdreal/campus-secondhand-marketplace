package com.toni.marketplace.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One in-app notification for one order-lifecycle transition (backlog
 * #157, Flyway V30).
 *
 * <p>Recipient and order references are plain ids (not
 * {@code @ManyToOne}), mirroring {@code Message} and the audit log: the
 * read path is the recipient's own paginated list, never association
 * fetching, and the migration declares no foreign keys on purpose — a
 * notification is a derived UI row, and referential cleanup of orders
 * or accounts must never be blocked by (or silently erase) it. The
 * {@code read_at} marker mirrors the message read state (#106):
 * {@code null} while unread, stamped once by the recipient's
 * mark-read call.
 */
@Entity
@Table(name = "notification")
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "recipient_id", nullable = false)
  private Long recipientId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(nullable = false, length = 24)
  private NotificationType type;

  @Column(name = "order_id", nullable = false)
  private Long orderId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** When the recipient marked this notification read; null while unread. */
  @Column(name = "read_at")
  private Instant readAt;

  protected Notification() {}

  public Notification(Long recipientId, NotificationType type, Long orderId) {
    this.recipientId = recipientId;
    this.type = type;
    this.orderId = orderId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getRecipientId() { return recipientId; }
  public NotificationType getType() { return type; }
  public Long getOrderId() { return orderId; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getReadAt() { return readAt; }

  /** Stamps the read marker once; a repeat mark keeps the first stamp. */
  public void markRead(Instant when) {
    if (this.readAt == null) {
      this.readAt = when;
    }
  }
}
