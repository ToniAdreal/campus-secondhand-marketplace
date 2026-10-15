package com.toni.marketplace.notification;

import java.time.Instant;

/**
 * The API shape of one notification (backlog #157). A dedicated DTO —
 * the entity is never serialized directly, mirroring the
 * {@code AuditLogDto} / {@code MessageDto} rule.
 */
public record NotificationDto(
    Long id,
    NotificationType type,
    Long orderId,
    Long offerId,
    Instant createdAt,
    Instant readAt) {

  public static NotificationDto from(Notification notification) {
    return new NotificationDto(
        notification.getId(),
        notification.getType(),
        notification.getOrderId(),
        notification.getOfferId(),
        notification.getCreatedAt(),
        notification.getReadAt());
  }
}
