package com.toni.marketplace.message;

import java.time.Instant;

/** Public shape of a message: ids, body, timestamp — no JPA types. */
public record MessageDto(
    Long id,
    Long itemId,
    Long senderId,
    Long receiverId,
    String body,
    Instant createdAt) {

  public static MessageDto from(Message message) {
    return new MessageDto(
        message.getId(),
        message.getItemId(),
        message.getSenderId(),
        message.getReceiverId(),
        message.getBody(),
        message.getCreatedAt());
  }
}
