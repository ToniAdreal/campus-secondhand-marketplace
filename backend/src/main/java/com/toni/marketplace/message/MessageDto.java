package com.toni.marketplace.message;

import java.time.Instant;

/**
 * Public shape of a message: ids, body, timestamps — no JPA types.
 * {@code readAt} is {@code null} until the receiver opens the thread
 * (backlog #106), so the UI can render read state honestly.
 */
public record MessageDto(
    Long id,
    Long itemId,
    Long senderId,
    Long receiverId,
    String body,
    Instant createdAt,
    Instant readAt) {

  public static MessageDto from(Message message) {
    return new MessageDto(
        message.getId(),
        message.getItemId(),
        message.getSenderId(),
        message.getReceiverId(),
        message.getBody(),
        message.getCreatedAt(),
        message.getReadAt());
  }
}
