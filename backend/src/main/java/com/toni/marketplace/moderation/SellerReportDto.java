package com.toni.marketplace.moderation;

import java.time.Instant;

/**
 * One report on one of the caller's own listings, as the seller sees
 * it (backlog #190): the report's public fields plus the state of the
 * seller's appeal of it, if any — {@code appealId} /
 * {@code appealStatus} / {@code appealListingRestored} are all null
 * while no appeal exists. The reporter's free-text note is NOT
 * projected here: the seller-facing surface names the report and its
 * outcome, it does not hand the seller the reporter's words.
 */
public record SellerReportDto(
    Long id,
    Long itemId,
    ReportReason reason,
    ReportStatus status,
    Instant createdAt,
    Long appealId,
    ReportAppealStatus appealStatus,
    Boolean appealListingRestored) {

  public static SellerReportDto from(ListingReport report, ReportAppeal appeal) {
    return new SellerReportDto(
        report.getId(),
        report.getItemId(),
        report.getReason(),
        report.getStatus(),
        report.getCreatedAt(),
        appeal == null ? null : appeal.getId(),
        appeal == null ? null : appeal.getStatus(),
        appeal == null ? null : appeal.getListingRestored());
  }
}
