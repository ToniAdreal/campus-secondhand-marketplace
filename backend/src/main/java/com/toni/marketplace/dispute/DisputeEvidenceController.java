package com.toni.marketplace.dispute;

import com.toni.marketplace.common.ApiResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dispute evidence photos (backlog #200). Authenticated like every
 * non-admin dispute route (the security chain's anyRequest rule;
 * anonymous callers get the 401 envelope); the buyer/seller/ADMIN
 * visibility scoping lives in {@link DisputeEvidenceService} and
 * answers 404 — never 403 — to a caller who is not a party to the
 * dispute, so the id leaks nothing.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "orders",
    description = "Buyer orders and the mock-PSP payment lifecycle")
@RequestMapping("/api/disputes/{disputeId}/evidence")
public class DisputeEvidenceController {

  private final DisputeEvidenceService evidenceService;

  public DisputeEvidenceController(DisputeEvidenceService evidenceService) {
    this.evidenceService = evidenceService;
  }

  /**
   * Attaches one evidence photo to an OPEN dispute. The file is
   * validated and stored by {@code ImageStorageService} (allowlisted
   * types, size cap, magic bytes) under a UUID name; a bad file is
   * the 400 envelope. A resolved dispute answers 422.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "evidence photo attached (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
          description = "file missing, not an allowlisted image, over the size cap, "
              + "or its bytes do not match the declared type (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown dispute id, or a dispute the caller is not a party to (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
          description = "dispute already resolved (envelope)")})
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<DisputeEvidenceDto> add(@PathVariable Long disputeId,
                                             @RequestParam("file") MultipartFile file) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return ApiResponse.ok(evidenceService.addEvidence(
        (Long) auth.getPrincipal(), isAdmin(auth), disputeId, file));
  }

  /** Lists a dispute's evidence, oldest first, as public upload URLs. */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
          description = "evidence photos, oldest first (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown dispute id, or a dispute the caller is not a party to (envelope)")})
  @GetMapping
  public ApiResponse<List<DisputeEvidenceDto>> list(@PathVariable Long disputeId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return ApiResponse.ok(evidenceService.listEvidence(
        (Long) auth.getPrincipal(), isAdmin(auth), disputeId));
  }

  /**
   * Removes one piece of evidence: the uploader or an ADMIN (the
   * other party gets 403; an evidence id from another dispute gets
   * 404). The stored file is deleted best-effort.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
          description = "unauthenticated (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
          description = "not the uploader of this evidence (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
          description = "unknown dispute/evidence id, or a dispute the caller is not a party to (envelope)")})
  @DeleteMapping("/{evidenceId}")
  public ApiResponse<Void> remove(@PathVariable Long disputeId,
                                  @PathVariable Long evidenceId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    evidenceService.removeEvidence(
        (Long) auth.getPrincipal(), isAdmin(auth), disputeId, evidenceId);
    return ApiResponse.ok(null);
  }

  private static boolean isAdmin(Authentication auth) {
    return auth.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
  }
}
