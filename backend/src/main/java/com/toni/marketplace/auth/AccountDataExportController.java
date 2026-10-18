package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <p>Backlog #188 adds the step-up gate: the download requires the
 * single-use grant minted by {@code POST /api/users/me/export/confirm}
 * after a fresh proof (current password, plus a TOTP/recovery code
 * when 2FA is enabled — the discipline of {@link
 * DataExportGrantService}), presented as the {@code X-Export-Grant}
 * header. A missing, expired or reused grant is the identical 401
 * envelope as a failed proof, so no answer reveals which step failed.
 *
 * <p>Authentication comes from the security chain's
 * {@code anyRequest().authenticated()} rule — both routes are scoped
 * to the JWT principal, there is no user-id parameter anywhere, so one
 * user's export can never be pointed at another user. Throttling is
 * the filter's own per-user data-export bucket (see
 * {@link AuthRateLimitFilter}); the audit rows are written by the
 * services, inside their transactions.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "account-data-export", description = "Self-service account data export")
@RequestMapping("/api/users/me")
public class AccountDataExportController {

  /** Header carrying the single-use step-up grant on the download (backlog #188). */
  public static final String EXPORT_GRANT_HEADER = "X-Export-Grant";

  private static final DateTimeFormatter FILENAME_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  private final AccountDataExportService exportService;
  private final DataExportGrantService grantService;

  public AccountDataExportController(AccountDataExportService exportService,
                                     DataExportGrantService grantService) {
    this.exportService = exportService;
    this.grantService = grantService;
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "200", description = "step-up proof accepted; single-use export grant minted (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "401", description = "wrong password or wrong code — identical for both (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "423", description = "second-factor attempts locked after repeated failures — see Retry-After (envelope)")})
  @PostMapping("/export/confirm")
  public ResponseEntity<ApiResponse<DataExportGrantResponse>> confirmExport(
      @Valid @RequestBody DataExportConfirmRequest request) {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    DataExportGrantService.IssuedGrant issued =
        grantService.confirm(userId, request.currentPassword(), request.code());
    return ResponseEntity.ok(
        ApiResponse.ok(new DataExportGrantResponse(issued.grant(), issued.expiresAt())));
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "200", description = "the caller's data export (JSON download)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "401", description = "unauthenticated, or the export grant is missing, expired or already used — identical for every grant failure (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "429", description = "export rate limit reached (envelope)")})
  @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<AccountDataExportDto> export(
      @RequestHeader(value = EXPORT_GRANT_HEADER, required = false) String exportGrant) {
    Long userId =
        (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    AccountDataExportDto dto = exportService.export(userId, exportGrant);
    String filename = "marketplace-data-user-" + dto.profile().id()
        + "-" + FILENAME_DATE.format(dto.exportedAt()) + ".json";
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + filename + "\"")
        .body(dto);
  }
}
