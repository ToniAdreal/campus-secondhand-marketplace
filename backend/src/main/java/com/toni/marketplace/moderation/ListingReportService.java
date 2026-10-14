package com.toni.marketplace.moderation;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Listing reports and the ADMIN moderation queue (backlog #160).
 *
 * <p>Write side ({@link #createReport}): any authenticated user may
 * report someone else's listing. Reporting your own listing is 422 —
 * the seller already has direct lifecycle controls (edit / mark sold /
 * delete is ADMIN-side), so a self-report can only be queue noise. One
 * OPEN report per (reporter, item): a repeat returns the existing row,
 * so double-clicks and client retries never spam the queue; once that
 * report is resolved, the same reporter may report the listing again
 * (a fresh row). Creation is throttled per user by
 * {@code AuthRateLimitFilter}'s report bucket.
 *
 * <p>Moderation side ({@link #dismiss} / {@link #action}): both
 * transitions are terminal and follow the order-lifecycle convention
 * (backlog #33/#49/#50) — repeating the same transition is idempotent
 * and returns the row unchanged, while attempting the opposite
 * transition on an already-resolved report is 422 (the decision was
 * made; it is not silently rewritten). {@link #action} additionally
 * withdraws the listing in the same transaction: an AVAILABLE listing
 * flips to {@link ItemStatus#WITHDRAWN} (the account-closure status,
 * backlog #158 — the row is kept so report/order history stays
 * intact). Mirroring that closure sweep, a RESERVED listing (a live
 * order) and a SOLD listing are left untouched — there is either an
 * in-flight transaction to protect or nothing left to withdraw.
 *
 * <p>Honest scope: no auto-hide. A listing stays fully visible until a
 * human ADMIN acts on a report, however many reports it collects — v1
 * has no "N reports hide the listing" rule.
 */
@Service
public class ListingReportService {

  private final ListingReportRepository reports;
  private final ItemRepository items;

  public ListingReportService(ListingReportRepository reports, ItemRepository items) {
    this.reports = reports;
    this.items = items;
  }

  /**
   * Files a report, or returns the reporter's existing OPEN report of
   * the same listing (the dedupe rule above). Unknown listing → 404;
   * the seller reporting their own listing → 422.
   */
  @Transactional
  public ListingReportDto createReport(Long reporterId, Long itemId, ReportCreateRequest request) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (item.getSellerId().equals(reporterId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "you cannot report your own listing");
    }
    return reports.findByReporterIdAndItemIdAndStatus(reporterId, itemId, ReportStatus.OPEN)
        .map(ListingReportDto::from)
        .orElseGet(() -> ListingReportDto.from(
            reports.save(new ListingReport(reporterId, itemId, request.reason(), request.note()))));
  }

  /** The ADMIN queue, paginated (the controller supplies newest-first); status filter optional. */
  @Transactional(readOnly = true)
  public Page<ListingReportDto> listReports(ReportStatus status, Pageable pageable) {
    Page<ListingReport> page = status == null
        ? reports.findAll(pageable)
        : reports.findByStatus(status, pageable);
    return page.map(ListingReportDto::from);
  }

  /**
   * Resolves a report without acting on the listing. Re-dismissing a
   * DISMISSED report is idempotent; dismissing an ACTIONED one is 422.
   * Unknown id → 404.
   */
  @Transactional
  public ListingReportDto dismiss(Long reportId) {
    ListingReport report = requireReport(reportId);
    if (report.getStatus() == ReportStatus.DISMISSED) {
      return ListingReportDto.from(report);
    }
    if (report.getStatus() == ReportStatus.ACTIONED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "report already actioned");
    }
    report.setStatus(ReportStatus.DISMISSED);
    return ListingReportDto.from(reports.save(report));
  }

  /**
   * Resolves a report by withdrawing its listing (see the class javadoc
   * for exactly which listing states flip), in the same transaction —
   * the report never reads ACTIONED while its listing is still on sale
   * as AVAILABLE. Re-actioning an ACTIONED report is idempotent (the
   * withdrawal already happened); actioning a DISMISSED one is 422.
   * Unknown id → 404.
   */
  @Transactional
  public ListingReportDto action(Long reportId) {
    ListingReport report = requireReport(reportId);
    if (report.getStatus() == ReportStatus.ACTIONED) {
      return ListingReportDto.from(report);
    }
    if (report.getStatus() == ReportStatus.DISMISSED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "report already dismissed");
    }
    items.findById(report.getItemId()).ifPresent(item -> {
      if (item.getStatus() == ItemStatus.AVAILABLE) {
        item.setStatus(ItemStatus.WITHDRAWN);
        items.save(item);
      }
    });
    report.setStatus(ReportStatus.ACTIONED);
    return ListingReportDto.from(reports.save(report));
  }

  private ListingReport requireReport(Long reportId) {
    return reports.findById(reportId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "report not found"));
  }
}
