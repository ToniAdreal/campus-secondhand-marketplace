package com.toni.marketplace.auth;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service account data export (backlog #172):
 * {@code GET /api/users/me/export}.
 *
 * <p>The success answer is the export document itself as a JSON
 * download — deliberately NOT the {@code {code,message,data}} envelope,
 * mirroring the seller sales CSV export (#159): the file a user saves
 * must be their data, not a wrapper around it. The
 * {@code Content-Disposition} filename carries the user id and the
 * export date (UTC), and the SPA saves the blob under exactly that
 * server filename. Error answers (401 anonymous, 429 throttled) keep
 * the JSON envelope like every other endpoint.
 *
 * <p>Authentication comes from the security chain's
 * {@code anyRequest().authenticated()} rule — the route is scoped to
 * the JWT principal, there is no user-id parameter anywhere, so one
 * user's export can never be pointed at another user. Throttling is
 * the filter's own per-user data-export bucket (see
 * {@link AuthRateLimitFilter}); the audit row is written by the
 * service, inside its transaction.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "account-data-export", description = "Self-service account data export")
@RequestMapping("/api/users/me")
public class AccountDataExportController {

  private static final DateTimeFormatter FILENAME_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  private final AccountDataExportService exportService;

  public AccountDataExportController(AccountDataExportService exportService) {
    this.exportService = exportService;
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "200", description = "the caller's data export (JSON download)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "401", description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "429", description = "export rate limit reached (envelope)")})
  @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AccountDataExportDto> export() {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    AccountDataExportDto dto = exportService.export(userId);
    String filename = "marketplace-data-user-" + dto.profile().id()
        + "-" + FILENAME_DATE.format(dto.exportedAt()) + ".json";
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + filename + "\"")
        .body(dto);
  }
}
