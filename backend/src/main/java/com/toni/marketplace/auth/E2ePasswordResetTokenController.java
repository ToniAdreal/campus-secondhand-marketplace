package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test-only read endpoint for the reset-token capture seam (backlog
 * #407, step C1 unblocking the #399 Playwright password-reset journey):
 * {@code GET /api/test/auth/password-reset-token?userId={id}} returns
 * the latest raw reset token recorded for that user by
 * {@link CapturingPasswordResetMailSender}, so the e2e flow can take the
 * token it could never get from a mailbox (the default sender logs with
 * the token redacted, and the token never appears in any API response).
 *
 * <p>This controller exists only when {@code app.auth.password-reset.
 * e2e-token-capture-enabled} is true — with the knob off (the default)
 * the bean is absent, the route is not in the OpenAPI document, and a
 * request to the path 404s at the dispatcher. The knob is set only on
 * the throwaway Playwright e2e backend, never on a real deployment:
 * with it on, this endpoint hands a live reset token to anyone who asks
 * for the user id. The endpoint is public at the security chain for the
 * same reason the mock-PSP webhook is public — the knob, not a Bearer
 * token, is the gate — see {@code SecurityConfig}.
 *
 * <p>The response mirrors issuance, not validity: a token stays
 * readable after it is confirmed or expires, and a fresh reset request
 * overwrites the previous token for the same user. Whether a returned
 * token still works is the confirm endpoint's business ({@code POST
 * /api/auth/password-reset/confirm} answers the identical 401 for an
 * unknown, used or expired token). No token recorded for the user id
 * answers 404 — including for a user id that does not exist, so the
 * endpoint leaks nothing about which ids are accounts.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "test-only",
    description = "E2e-only seams, present only when their opt-in knob is on")
@RequestMapping("/api/test/auth")
@ConditionalOnProperty(prefix = "app.auth.password-reset",
    name = "e2e-token-capture-enabled", havingValue = "true")
public class E2ePasswordResetTokenController {

  private final CapturingPasswordResetMailSender capturingSender;

  public E2ePasswordResetTokenController(CapturingPasswordResetMailSender capturingSender) {
    this.capturingSender = capturingSender;
  }

  /** The latest raw reset token recorded for {@code userId}. */
  public record PasswordResetTokenResponse(String token) {
  }

  @io.swagger.v3.oas.annotations.Operation(
      summary = "Read the latest captured password-reset token for a user "
          + "(e2e-only; 404 when the capture knob is off or no token was issued)")
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "200", description = "latest issued token (envelope)"),
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "404", description = "no token recorded for this user id (envelope)")})
  @GetMapping("/password-reset-token")
  public ResponseEntity<ApiResponse<PasswordResetTokenResponse>> readToken(
      @RequestParam long userId) {
    String token = capturingSender.findToken(userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "no password-reset token recorded for this user"));
    return ResponseEntity.ok(ApiResponse.ok(new PasswordResetTokenResponse(token)));
  }
}
