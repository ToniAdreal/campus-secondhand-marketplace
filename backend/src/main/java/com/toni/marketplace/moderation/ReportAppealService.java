package com.toni.marketplace.moderation;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationService;
import com.toni.marketplace.notification.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Report appeals (backlog #190): a seller whose listing was withdrawn
 * when an ADMIN actioned a report (#160) had no recourse — both report
 * transitions are terminal. An appeal is that recourse: the seller
 * asks an ADMIN to reconsider, and an upheld appeal restores the
 * listing when it is still withdrawn.
 *
 * <p>Open ({@link #createAppeal}):
 * <ul>
 *   <li>Seller only — anyone who is not the listing's seller gets 403,
 *       checked before any state guard, mirroring
 *       {@code DisputeService}, so a report id never leaks what the
 *       caller may do with it. Unknown report → 404; anonymous → the
 *       chain's 401 envelope.</li>
 *   <li>Only an ACTIONED report can be appealed: an OPEN report has
 *       not been decided yet and a DISMISSED one took no action, so
 *       both answer 422.</li>
 *   <li>One appeal per report, ever: filing while one is OPEN returns
 *       the open appeal unchanged (no second row, audit row or
 *       notification); re-appealing after a resolution is 422 — the
 *       decision was made. The V50 {@code live_report_id} guard column
 *       is the database-level arbiter for racing opens.</li>
 * </ul>
 *
 * <p>Resolution (ADMIN only, on the controllers):
 * <ul>
 *   <li>{@link #uphold}: the listing flips WITHDRAWN → AVAILABLE only
 *       when it is actually still WITHDRAWN; a listing that moved on
 *       in the meantime (RESERVED by a live order, SOLD, EXPIRED) is
 *       left untouched — an uphold never touches orders — and the row
 *       records which case applied ({@code listingRestored}).</li>
 *   <li>{@link #reject}: the withdrawal stands; the listing is
 *       untouched.</li>
 *   <li>Repeating the same resolution is idempotent and returns the
 *       appeal unchanged; attempting the opposite resolution on an
 *       already-resolved appeal is 422. Racing resolutions fail fast
 *       to 409 via {@code @Version}.</li>
 * </ul>
 *
 * <p>Audit + notifications join each transition's own transaction,
 * mirroring {@code DisputeService}: open writes REPORT_APPEAL_OPENED
 * (actor = the seller); each resolution writes its own audit action
 * (actor = the resolving ADMIN) and notifies the seller. The
 * notification copy is deliberately generic ("Your appeal was
 * upheld") — the notification row cannot carry the
 * {@code listingRestored} distinction, so it never claims the listing
 * was restored; the appeal DTO carries that fact. A rolled-back
 * transition writes neither.
 *
 * <p>Honest scope: reason text only, no evidence upload in v1; the
 * original report row is never rewritten by an appeal — the appeal is
 * a separate record of the reconsideration.
 */
@Service
public class ReportAppealService {

  private final ReportAppealRepository appeals;
  private final ListingReportRepository reports;
  private final ItemRepository items;
  private final AuditService audit;
  private final NotificationService notifications;
  private final Clock clock;

  @Autowired
  public ReportAppealService(ReportAppealRepository appeals,
                             ListingReportRepository reports,
                             ItemRepository items,
                             AuditService audit,
                             NotificationService notifications,
                             Clock clock) {
    this.appeals = appeals;
    this.reports = reports;
    this.items = items;
    this.audit = audit;
    this.notifications = notifications;
    this.clock = clock;
  }

  /** Constructor for direct unit tests that do not assert on audit/notifications. */
  public ReportAppealService(ReportAppealRepository appeals,
                             ListingReportRepository reports,
                             ItemRepository items) {
    this(appeals, reports, items, AuditService.noop(),
        NotificationService.noop(), Clock.systemUTC());
  }

  private Clock clock() {
    return clock != null ? clock : Clock.systemUTC();
  }

  private AuditService audit() {
    return audit != null ? audit : AuditService.noop();
  }

  private NotificationService notifications() {
    return notifications != null ? notifications : NotificationService.noop();
  }

  /**
   * Files an appeal on an ACTIONED report (see the class javadoc for
   * the full rule set), or returns the already-open appeal for the
   * report unchanged.
   */
  @Transactional
  public ReportAppealDto createAppeal(Long sellerId, Long reportId, String reason) {
    ListingReport report = requireReport(reportId);
    Item item = requireListingOf(report);
    requireSeller(sellerId, item);
    // The live appeal is the answer to a repeat POST — checked before
    // the state guards so a retry never 422s against its own appeal.
    var open = appeals.findByReportIdAndStatus(reportId, ReportAppealStatus.OPEN);
    if (open.isPresent()) {
      return ReportAppealDto.from(open.get(), item.getId());
    }
    if (appeals.findByReportId(reportId).stream()
        .anyMatch(a -> a.getStatus() != ReportAppealStatus.OPEN)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "this report was already appealed and the appeal was resolved");
    }
    if (report.getStatus() != ReportStatus.ACTIONED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "only an actioned report can be appealed");
    }
    try {
      ReportAppeal appeal = new ReportAppeal(reportId, sellerId, reason);
      ReportAppealDto dto = ReportAppealDto.from(appeals.save(appeal), item.getId());
      appeals.flush();
      audit().record(sellerId, AuditAction.REPORT_APPEAL_OPENED,
          AuditTargetType.REPORT_APPEAL, appeal.getId());
      return dto;
    } catch (DataIntegrityViolationException e) {
      // Lost the uq_report_appeal_live_report race: a concurrent open
      // inserted the live appeal first. Same client outcome as the
      // dispute version race — 409, retry returns the open appeal.
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "appeal was filed concurrently, please retry");
    }
  }

  /**
   * The seller's own appeal state for one report: 200 with the appeal,
   * or 404 when the seller never appealed it. Same 403/404 discipline
   * as {@link #createAppeal} — a non-seller gets 403 before any state
   * is read, an unknown report 404.
   */
  @Transactional(readOnly = true)
  public ReportAppealDto getAppealForReport(Long sellerId, Long reportId) {
    ListingReport report = requireReport(reportId);
    Item item = requireListingOf(report);
    requireSeller(sellerId, item);
    return appeals.findByReportId(reportId).stream().findFirst()
        .map(appeal -> ReportAppealDto.from(appeal, item.getId()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "no appeal for this report"));
  }

  /** The seller's own appeals, paginated (the controller supplies newest-first). */
  @Transactional(readOnly = true)
  public Page<ReportAppealDto> listSellerAppeals(Long sellerId, Pageable pageable) {
    return appeals.findBySellerId(sellerId, pageable).map(this::toDto);
  }

  /**
   * Reports filed against the caller's own listings, paginated (the
   * controller supplies newest-first), each with its appeal state —
   * the read the SPA's My listings page needs to offer the appeal
   * action on a withdrawn-by-report listing. The listing ids are
   * resolved from the caller, never from a parameter.
   */
  @Transactional(readOnly = true)
  public Page<SellerReportDto> listSellerReports(Long sellerId, Pageable pageable) {
    List<Long> itemIds = items.findBySellerId(sellerId).stream()
        .map(Item::getId).toList();
    if (itemIds.isEmpty()) {
      return Page.empty(pageable);
    }
    Page<ListingReport> page = reports.findByItemIdIn(itemIds, pageable);
    Map<Long, ReportAppeal> appealByReport = page.getContent().stream()
        .map(report -> appeals.findByReportId(report.getId()))
        .flatMap(List::stream)
        .collect(Collectors.toMap(ReportAppeal::getReportId, Function.identity(),
            (first, ignored) -> first));
    return page.map(report -> SellerReportDto.from(report, appealByReport.get(report.getId())));
  }

  /** The ADMIN queue, paginated (the controller supplies newest-first); status filter optional. */
  @Transactional(readOnly = true)
  public Page<ReportAppealDto> listAppeals(ReportAppealStatus status, Pageable pageable) {
    Page<ReportAppeal> page = status == null
        ? appeals.findAll(pageable)
        : appeals.findByStatus(status, pageable);
    return page.map(this::toDto);
  }

  /**
   * Resolves an appeal in the seller's favour (see the class
   * javadoc). ADMIN authorization lives on the controller;
   * {@code adminId} is the audit actor.
   */
  @Transactional
  public ReportAppealDto uphold(Long adminId, Long appealId) {
    ReportAppeal appeal = requireAppeal(appealId);
    if (appeal.getStatus() == ReportAppealStatus.UPHELD) {
      return toDto(appeal);
    }
    if (appeal.getStatus() == ReportAppealStatus.REJECTED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "appeal already rejected");
    }
    try {
      boolean restored = false;
      Long itemId = itemIdOf(appeal);
      Item item = itemId == null ? null : items.findById(itemId).orElse(null);
      if (item != null && item.getStatus() == ItemStatus.WITHDRAWN) {
        item.setStatus(ItemStatus.AVAILABLE);
        items.save(item);
        restored = true;
      }
      appeal.setStatus(ReportAppealStatus.UPHELD);
      appeal.setListingRestored(restored);
      appeal.setResolvedAt(Instant.now(clock()));
      ReportAppealDto dto = toDto(appeals.save(appeal));
      appeals.flush();
      audit().record(adminId, AuditAction.REPORT_APPEAL_UPHELD,
          AuditTargetType.REPORT_APPEAL, appealId);
      notifications().recordReportAppeal(appeal.getSellerId(),
          NotificationType.REPORT_APPEAL_UPHELD, appealId);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "appeal was updated concurrently, please retry");
    }
  }

  /**
   * Resolves an appeal against the seller (see the class javadoc):
   * the withdrawal stands and the listing is untouched. ADMIN
   * authorization lives on the controller; {@code adminId} is the
   * audit actor.
   */
  @Transactional
  public ReportAppealDto reject(Long adminId, Long appealId) {
    ReportAppeal appeal = requireAppeal(appealId);
    if (appeal.getStatus() == ReportAppealStatus.REJECTED) {
      return toDto(appeal);
    }
    if (appeal.getStatus() == ReportAppealStatus.UPHELD) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "appeal already upheld");
    }
    try {
      appeal.setStatus(ReportAppealStatus.REJECTED);
      appeal.setResolvedAt(Instant.now(clock()));
      ReportAppealDto dto = toDto(appeals.save(appeal));
      appeals.flush();
      audit().record(adminId, AuditAction.REPORT_APPEAL_REJECTED,
          AuditTargetType.REPORT_APPEAL, appealId);
      notifications().recordReportAppeal(appeal.getSellerId(),
          NotificationType.REPORT_APPEAL_REJECTED, appealId);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "appeal was updated concurrently, please retry");
    }
  }

  private ReportAppealDto toDto(ReportAppeal appeal) {
    return ReportAppealDto.from(appeal, itemIdOf(appeal));
  }

  /** The appealed report's listing id; null only if the report row itself is gone. */
  private Long itemIdOf(ReportAppeal appeal) {
    return reports.findById(appeal.getReportId())
        .map(ListingReport::getItemId).orElse(null);
  }

  private ListingReport requireReport(Long reportId) {
    return reports.findById(reportId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "report not found"));
  }

  private ReportAppeal requireAppeal(Long appealId) {
    return appeals.findById(appealId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "appeal not found"));
  }

  private Item requireListingOf(ListingReport report) {
    return items.findById(report.getItemId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "report not found"));
  }

  private static void requireSeller(Long sellerId, Item item) {
    if (!item.getSellerId().equals(sellerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the listing's seller can appeal this report");
    }
  }
}
