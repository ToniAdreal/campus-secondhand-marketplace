package com.toni.marketplace.moderation;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingReportRepository extends JpaRepository<ListingReport, Long> {

  /**
   * The reporter's still-open report of one listing, if any — the
   * dedupe lookup behind "one OPEN report per reporter+item"
   * (backlog #160): a repeat report returns this row instead of
   * inserting a duplicate.
   */
  Optional<ListingReport> findByReporterIdAndItemIdAndStatus(
      Long reporterId, Long itemId, ReportStatus status);

  /** The ADMIN queue narrowed to one status; the service supplies newest-first sort. */
  Page<ListingReport> findByStatus(ReportStatus status, Pageable pageable);
}
