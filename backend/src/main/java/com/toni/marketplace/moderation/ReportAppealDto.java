package com.toni.marketplace.moderation;

import java.time.Instant;

/**
 * Public shape of a report appeal (backlog #190): ids and state, no
 * JPA types, no lock version. {@code itemId} is the appealed report's
 * listing, denormalised onto the DTO so the SPA can render the appeal
 * next to its listing without a second lookup; {@code listingRestored}
 * is null until an uphold resolution writes it (see
 * {@link ReportAppeal}); {@code resolvedAt} is null while the appeal
 * is OPEN.
 */
public record ReportAppealDto(
    Long id,
    Long reportId,
    Long itemId,
    Long sellerId,
    String reason,
    ReportAppealStatus status,
    Boolean listingRestored,
    Instant createdAt,
    Instant resolvedAt) {

  public static ReportAppealDto from(ReportAppeal appeal, Long itemId) {
    return new ReportAppealDto(
        appeal.getId(),
        appeal.getReportId(),
        itemId,
        appeal.getSellerId(),
        appeal.getReason(),
        appeal.getStatus(),
        appeal.getListingRestored(),
        appeal.getCreatedAt(),
        appeal.getResolvedAt());
  }
}
