package com.toni.marketplace.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One offline message from one user to another about a listing.
 *
 * <p>Participant and listing references are plain ids (not {@code @ManyToOne})
 * on purpose: the conversation read path is a flat index scan on
 * {@code (item_id, created_at)} and never needs association fetching — this is
 * the same DTO-projection instinct as the Item list view (N+1 fix, #7), applied
 * up front. Referential integrity is enforced by the Flyway V7 foreign keys,
 * not by the ORM.
 *
 * <p>The storage column is {@code message_body}: {@code BODY} is a keyword in
 * H2's parser, so an unquoted {@code body} column would fail local schema
 * validation.
 */
@Entity
@Table(name = "messages")
public class Message {

  /** v1 honest-scope cap: longer bodies must go through real chat tooling. */
  public static final int MAX_BODY_LENGTH = 2000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  @Column(name = "sender_id", nullable = false)
  private Long senderId;

  @Column(name = "receiver_id", nullable = false)
  private Long receiverId;

  @Column(name = "message_body", nullable = false, length = MAX_BODY_LENGTH)
  private String body;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * When the receiver first opened the thread containing this message
   * (Flyway V24, backlog #106); {@code null} while unread. Only the
   * receiver's thread view stamps it — a sender viewing their own sent
   * rows never marks them read.
   */
  @Column(name = "read_at")
  private Instant readAt;

  protected Message() {}

  public Message(Long itemId, Long senderId, Long receiverId, String body) {
    this.itemId = itemId;
    this.senderId = senderId;
    this.receiverId = receiverId;
    this.body = body;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getItemId() { return itemId; }
  public Long getSenderId() { return senderId; }
  public Long getReceiverId() { return receiverId; }
  public String getBody() { return body; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getReadAt() { return readAt; }

  /** Stamps the read marker once; a second view keeps the first stamp. */
  public void markRead(Instant when) {
    if (this.readAt == null) {
      this.readAt = when;
    }
  }
}
