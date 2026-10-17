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

  /**
   * The order this notification is about. Nullable since backlog #168
   * (Flyway V35): an OFFER_DECLINED notification has no order — declining
   * an offer never creates one — while every ORDER_* row and every
   * OFFER_ACCEPTED row carries the id.
   */
  @Column(name = "order_id")
  private Long orderId;

  /**
   * The price offer this notification is about (backlog #168, Flyway
   * V34); null on pure order-lifecycle rows.
   */
  @Column(name = "offer_id")
  private Long offerId;

  /**
   * The saved search this notification is about (backlog #182, Flyway
   * V45); only SAVED_SEARCH_MATCH rows carry it, so the SPA can link
   * back to that search's browse filters. Null on every other type.
   */
  @Column(name = "saved_search_id")
  private Long savedSearchId;

  /**
   * How many NEW listings matched in the matcher run that wrote this
   * row (backlog #182, Flyway V45): the notification is batched — one
   * row per saved search per run saying N, never N rows. Only
   * SAVED_SEARCH_MATCH rows carry it.
   */
  @Column(name = "matched_listing_count")
  private Integer matchedListingCount;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** When the recipient marked this notification read; null while unread. */
  @Column(name = "read_at")
  private Instant readAt;

  protected Notification() {}

  public Notification(Long recipientId, NotificationType type, Long orderId) {
    this(recipientId, type, orderId, null);
  }

  public Notification(Long recipientId, NotificationType type, Long orderId, Long offerId) {
    this.recipientId = recipientId;
    this.type = type;
    this.orderId = orderId;
    this.offerId = offerId;
  }

  /**
   * Saved-search match factory (backlog #182): no order or offer —
   * the row names the saved search and the batched match count.
   */
  public static Notification savedSearchMatch(Long recipientId, Long savedSearchId,
                                              int matchedListingCount) {
    Notification notification =
        new Notification(recipientId, NotificationType.SAVED_SEARCH_MATCH, null, null);
    notification.savedSearchId = savedSearchId;
    notification.matchedListingCount = matchedListingCount;
    return notification;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getRecipientId() { return recipientId; }
  public NotificationType getType() { return type; }
  public Long getOrderId() { return orderId; }
  public Long getOfferId() { return offerId; }
  public Long getSavedSearchId() { return savedSearchId; }
  public Integer getMatchedListingCount() { return matchedListingCount; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getReadAt() { return readAt; }

  /** Stamps the read marker once; a repeat mark keeps the first stamp. */
  public void markRead(Instant when) {
    if (this.readAt == null) {
      this.readAt = when;
    }
  }
}
