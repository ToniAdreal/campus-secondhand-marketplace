package com.toni.marketplace.message;

import java.time.Instant;

/**
 * Public shape of a message: ids, body, timestamps — no JPA types.
 * {@code readAt} is {@code null} until the receiver opens the thread
 * (backlog #106), so the UI can render read state honestly.
 * {@code editedAt} / {@code deletedAt} (backlog #206, Flyway V57) are
 * {@code null} while the event never happened; a deleted message's
 * {@code body} is always {@link Message#TOMBSTONE_BODY} — the DTO is
 * built from the stored row, whose body was replaced at delete time,
 * so no read path can leak the original wording.
 */
public record MessageDto(
    Long id,
    Long itemId,
    Long senderId,
    Long receiverId,
    String body,
    Instant createdAt,
    Instant readAt,
    Instant editedAt,
    Instant deletedAt) {

  public static MessageDto from(Message message) {
    return new MessageDto(
        message.getId(),
        message.getItemId(),
        message.getSenderId(),
        message.getReceiverId(),
        message.getBody(),
        message.getCreatedAt(),
        message.getReadAt(),
        message.getEditedAt(),
        message.getDeletedAt());
  }
}
