package com.toni.marketplace.moderation;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seller report appeals (backlog #190). Authenticated like every
 * seller route (the security chain's anyRequest rule; anonymous
 * callers get the 401 envelope); the seller scoping lives in
 * {@link ReportAppealService} — the seller id always comes from the
 * JWT principal, never a parameter. The ADMIN resolution endpoints
 * live on {@link AdminAppealController}.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "seller", description = "Seller report appeals")
@RequestMapping("/api/seller")
public class SellerAppealController {

  private final ReportAppealService appealService;

  public SellerAppealController(ReportAppealService appealService) {
    this.appealService = appealService;
  }

  /**
   * Reports filed against the caller's own listings, newest first,
   * each with its appeal state — the read the My listings page uses
   * to offer the appeal action on a withdrawn-by-report listing.
   */
  @GetMapping("/reports")
  public ApiResponse<Page<SellerReportDto>> listReports(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(appealService.listSellerReports(currentUserId(), pageable));
  }

  /** The caller's own appeals, newest first. */
  @GetMapping("/appeals")
  public ApiResponse<Page<ReportAppealDto>> listAppeals(
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ApiResponse.ok(appealService.listSellerAppeals(currentUserId(), pageable));
  }

  /**
   * Files an appeal on an ACTIONED report of one of the caller's own
   * listings: the listing's seller only (403 otherwise), only on an
   * ACTIONED report (OPEN/DISMISSED → 422), unknown report → 404.
   * Filing while an appeal is already open returns that appeal
   * unchanged; re-appealing a resolved report is 422.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "appeal filed — or the already-open appeal for this report (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "validation failure — reason missing/blank or over 500 characters (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the listing's seller (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown report id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "report is not ACTIONED, or its appeal was already resolved (envelope)")})
  @PostMapping("/reports/{id}/appeal")
  public ApiResponse<ReportAppealDto> appeal(@PathVariable Long id,
                                             @Valid @RequestBody AppealCreateRequest request) {
    return ApiResponse.ok(appealService.createAppeal(currentUserId(), id, request.reason()));
  }

  /**
   * The caller's appeal state for one report: 200 with the appeal, or
   * 404 when no appeal exists for it. Same 403/404 discipline as the
   * POST.
   */
  @GetMapping("/reports/{id}/appeal")
  public ApiResponse<ReportAppealDto> getAppeal(@PathVariable Long id) {
    return ApiResponse.ok(appealService.getAppealForReport(currentUserId(), id));
  }

  private static Long currentUserId() {
    return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }
}
