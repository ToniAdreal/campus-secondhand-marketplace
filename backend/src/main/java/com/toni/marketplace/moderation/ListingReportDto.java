package com.toni.marketplace.moderation;

import java.time.Instant;

/**
 * The API shape of one listing report (backlog #160). A dedicated DTO —
 * the entity is never serialized directly, mirroring the
 * {@code AuditLogDto} / {@code NotificationDto} rule. Enum fields
 * serialize as their constant names — the same allowlisted values the
 * ADMIN queue's status filter accepts.
 */
public record ListingReportDto(
    Long id,
    Long reporterId,
    Long itemId,
    ReportReason reason,
    String note,
    ReportStatus status,
    Instant createdAt) {

  public static ListingReportDto from(ListingReport report) {
    return new ListingReportDto(
        report.getId(),
        report.getReporterId(),
        report.getItemId(),
        report.getReason(),
        report.getNote(),
        report.getStatus(),
        report.getCreatedAt());
  }
}
