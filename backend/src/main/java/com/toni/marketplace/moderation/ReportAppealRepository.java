package com.toni.marketplace.moderation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportAppealRepository extends JpaRepository<ReportAppeal, Long> {

  /**
   * The still-open appeal of one report, if any — the lookup behind
   * "filing a second appeal while one is OPEN returns the open appeal"
   * (backlog #190): a repeat POST returns this row instead of
   * inserting a duplicate. The {@code uq_report_appeal_live_report}
   * guard column is the database-level arbiter under races; this read
   * is the friendly path.
   */
  Optional<ReportAppeal> findByReportIdAndStatus(Long reportId, ReportAppealStatus status);

  /**
   * Every appeal ever filed for one report, in any state — the lookup
   * behind "re-appealing a resolved report is 422": any resolved row
   * here means the report's one appeal has already been decided.
   */
  List<ReportAppeal> findByReportId(Long reportId);

  /** The ADMIN queue narrowed to one status; the service supplies newest-first sort. */
  Page<ReportAppeal> findByStatus(ReportAppealStatus status, Pageable pageable);

  /** One seller's own appeals, paginated (the service supplies newest-first sort). */
  Page<ReportAppeal> findBySellerId(Long sellerId, Pageable pageable);
}
