package com.toni.marketplace.moderation;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reporter side of listing moderation (backlog #160):
 * {@code POST /api/items/{id}/report}. The reporter is the JWT
 * principal — there is no reporter parameter anywhere, so a report can
 * never be filed in someone else's name. Authentication comes from the
 * security chain's {@code anyRequest().authenticated()} rule: anonymous
 * callers get 401 in the envelope.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "items", description = "Listings")
public class ReportController {

  private final ListingReportService reportService;

  public ReportController(ListingReportService reportService) {
    this.reportService = reportService;
  }

  /**
   * Reports a listing. A repeat while the caller's earlier report is
   * still OPEN returns that existing report (no duplicate row). The
   * seller reporting their own listing is 422; an unknown listing is
   * 404; a missing/invalid reason is 400. Creation is throttled per
   * user by the rate-limit filter's report bucket (429).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "report filed — or the caller's existing OPEN report of this listing (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "missing reason or invalid payload (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown listing id (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "reporting your own listing (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
          description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/api/items/{id}/report")
  public ApiResponse<ListingReportDto> report(@PathVariable Long id,
                                              @Valid @RequestBody ReportCreateRequest request) {
    Long reporterId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ApiResponse.ok(reportService.createReport(reporterId, id, request));
  }
}
