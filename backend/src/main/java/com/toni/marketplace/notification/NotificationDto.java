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
    /** The saved search a SAVED_SEARCH_MATCH row names (backlog #182); null otherwise. */
    Long savedSearchId,
    /** Batched new-match count on a SAVED_SEARCH_MATCH row (backlog #182); null otherwise. */
    Integer matchedListingCount,
    Instant createdAt,
    Instant readAt) {

  public static NotificationDto from(Notification notification) {
    return new NotificationDto(
        notification.getId(),
        notification.getType(),
        notification.getOrderId(),
        notification.getOfferId(),
        notification.getSavedSearchId(),
        notification.getMatchedListingCount(),
        notification.getCreatedAt(),
        notification.getReadAt());
  }
}
