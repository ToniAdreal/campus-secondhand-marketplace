package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public auth endpoints. The refresh token travels exclusively in the
 * httpOnly {@code refresh_token} cookie (never in the JSON body), so page
 * JavaScript cannot exfiltrate it; the access token goes to the SPA in memory.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "auth", description = "Authentication, sessions and 2FA")
@RequestMapping("/api/auth")
public class AuthController {

  private static final String REFRESH_COOKIE = "refresh_token";

  private final AuthService auth;
  private final PasswordResetService passwordReset;
  private final JwtProperties jwtProps;

  public AuthController(AuthService auth, PasswordResetService passwordReset,
                        JwtProperties jwtProps) {
    this.auth = auth;
    this.passwordReset = passwordReset;
    this.jwtProps = jwtProps;
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "validation failure or weak password (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "username or email already taken (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/register")
  public ResponseEntity<ApiResponse<AuthResponse>> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    return ok(auth.register(request.username(), request.email(), request.password(),
        SessionMeta.of(http)));
  }

  /**
   * Password login. For a TOTP-enabled account the password check alone
   * does not finish the login: the answer is HTTP 202 with a short-lived
   * signed challenge (no token pair, no refresh cookie), which the client
   * exchanges — with the 6-digit code — at {@code POST
   * /api/auth/2fa/authenticate}.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "token pair issued (refresh token in the httpOnly cookie)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "TOTP enabled: exchange the signed challenge at /api/auth/2fa/authenticate"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "invalid credentials — identical for unknown identifiers (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "account locked after repeated failures — see Retry-After (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/login")
  public ResponseEntity<?> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    AuthService.LoginResult result =
        auth.login(request.usernameOrEmail(), request.password(), SessionMeta.of(http));
    if (result instanceof AuthService.LoginResult.Pair pair) {
      return ok(pair.result());
    }
    AuthService.LoginResult.Challenge challenge = (AuthService.LoginResult.Challenge) result;
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.ok(
            new TotpChallengeResponse(challenge.challengeToken(), challenge.expiresAt())));
  }

  /**
   * Starts TOTP enrollment for the caller (authenticated): returns the
   * Base32 shared secret and the otpauth:// URI to scan into an
   * authenticator app. Re-running setup regenerates the secret and resets
   * enrollment — complete {@code /2fa/enable} again afterwards.
   */
  @PostMapping("/2fa/setup")
  public ResponseEntity<ApiResponse<TotpSetupResponse>> setupTotp(
      @AuthenticationPrincipal Long userId) {
    AuthService.TotpSetup setup = auth.setupTotp(userId);
    return ResponseEntity.ok(
        ApiResponse.ok(new TotpSetupResponse(setup.secret(), setup.otpauthUri())));
  }

  /**
   * Completes TOTP enrollment: a valid 6-digit code (checked against the
   * stored secret) flips 2FA on for the caller. Wrong code → 400; no setup
   * yet → 400. The response carries the account's 10 one-time recovery
   * codes (backlog #91) — shown once, only their hashes are stored — for
   * the user to save against losing their authenticator device.
   */
  @PostMapping("/2fa/enable")
  public ResponseEntity<ApiResponse<TotpEnableResponse>> enableTotp(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody TotpCodeRequest request) {
    return ResponseEntity.ok(
        ApiResponse.ok(new TotpEnableResponse(auth.enableTotp(userId, request.code()))));
  }

  /**
   * How many of the caller's recovery codes remain unused (backlog #91,
   * authenticated). A count only — the codes themselves are never
   * readable back after the enable response.
   */
  @GetMapping("/2fa/recovery-codes/count")
  public ResponseEntity<ApiResponse<TotpRecoveryCodeCountResponse>> recoveryCodeCount(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok(
        ApiResponse.ok(new TotpRecoveryCodeCountResponse(auth.recoveryCodeCount(userId))));
  }

  /**
   * The second-factor exchange (public — the caller is not authenticated
   * yet): the 202 challenge plus the current 6-digit code — or a
   * one-time recovery code (backlog #91), which the successful exchange
   * consumes. On success the response is the normal token pair with the
   * httpOnly refresh cookie, exactly like a finished login. Bad/expired
   * challenge or wrong code → 401.
   */
  @PostMapping("/2fa/authenticate")
  public ResponseEntity<ApiResponse<AuthResponse>> authenticateTotp(
      @Valid @RequestBody TotpAuthenticateRequest request, HttpServletRequest http) {
    return ok(auth.authenticateTotp(request.challenge(), request.code(), SessionMeta.of(http)));
  }

  /**
   * Starts a password reset (backlog #90, public like login): the answer
   * is ALWAYS this identical 200 envelope, whether or not the identifier
   * belongs to an account — no enumeration oracle. When the account
   * exists, a single-use token (30 min, hash-only storage) is delivered
   * through the mail seam; it never appears in any response.
   */
  @PostMapping("/password-reset")
  public ResponseEntity<ApiResponse<Void>> requestPasswordReset(
      @Valid @RequestBody PasswordResetRequest request) {
    passwordReset.requestReset(request.usernameOrEmail());
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Completes a password reset (backlog #90, public — the token IS the
   * credential): consumes the token, applies the strength policy to the
   * new password (weak → 400), bumps the user's token version and revokes
   * every refresh family, so all pre-reset sessions die. Unknown, used or
   * expired tokens → the identical 401. The SPA reset pages landed in
   * backlog #99 (/forgot-password + /reset-password?token=); this is
   * the API contract they use.
   */
  @PostMapping("/password-reset/confirm")
  public ResponseEntity<ApiResponse<Void>> confirmPasswordReset(
      @Valid @RequestBody PasswordResetConfirmRequest request) {
    passwordReset.confirmReset(request.token(), request.newPassword());
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<AuthResponse>> refresh(
      @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
      HttpServletRequest http) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new InvalidRefreshTokenException("missing refresh token");
    }
    return ok(auth.refresh(refreshToken, SessionMeta.of(http)));
  }

  /**
   * Returns the caller's own profile — session restore for the SPA. The
   * access token and the user summary live only in memory, so a page reload
   * wipes them; the httpOnly refresh cookie survives, and after the client
   * silently refreshes it, this endpoint tells the client who is logged in.
   * Requires a Bearer access token (SecurityConfig); anonymous callers get
   * the JSON 401 envelope. The body is the {@link AuthResponse.UserSummary}
   * projection — never the {@link User} entity — so the password hash can
   * never leak into a response.
   */
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<AuthResponse.UserSummary>> me(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok(ApiResponse.ok(AuthResponse.UserSummary.of(auth.me(userId))));
  }

  /**
   * Changes the caller's password (identified by the Bearer access token —
   * SecurityConfig requires authentication for this route). The current
   * password is required; the new one must pass the strength policy. On
   * success the caller's other sessions are revoked server-side (their
   * refresh cookies 401 afterwards) while the current session receives a
   * fresh refresh cookie — the caller stays logged in.
   */
  @PostMapping("/password")
  public ResponseEntity<ApiResponse<AuthResponse>> changePassword(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody PasswordChangeRequest request, HttpServletRequest http) {
    return ok(auth.changePassword(userId, request.currentPassword(), request.newPassword(),
        SessionMeta.of(http)));
  }

  /**
   * Per-session management (backlog #62): lists the caller's live
   * refresh-token sessions — one entry per device/browser that holds a
   * valid refresh token — with the device label (User-Agent) and IP
   * captured at login/refresh. The session the caller presented (their
   * {@code refresh_token} cookie, matched via the token's jti → family) is
   * flagged {@code current}; an absent cookie flags nothing. Fixes the
   * long-standing scope-table limitation "logout kills every session on
   * every device": individual sessions can now be inspected and revoked
   * with {@code DELETE /api/auth/sessions/{id}}.
   */
  @GetMapping("/sessions")
  public ResponseEntity<ApiResponse<List<SessionDto>>> sessions(
      @AuthenticationPrincipal Long userId,
      @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken) {
    return ResponseEntity.ok(ApiResponse.ok(auth.sessions(userId, refreshToken)));
  }

  /**
   * Revokes one of the caller's sessions (its whole refresh-token family)
   * without touching their other sessions. Unknown ids — or ids belonging
   * to another user — are 404 (no cross-user oracle). The {@code id} is the
   * session id from {@code GET /api/auth/sessions} (the family's root jti,
   * stable across rotations).
   */
  @DeleteMapping("/sessions/{id}")
  public ResponseEntity<ApiResponse<Void>> deleteSession(
      @AuthenticationPrincipal Long userId,
      @PathVariable("id") String sessionId) {
    auth.revokeSession(userId, sessionId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Logs out the caller (identified by the Bearer access token — the
   * SecurityConfig requires authentication for this route): every refresh
   * token of the user is revoked server-side, and the httpOnly
   * {@code refresh_token} cookie is cleared with an expired
   * {@code Set-Cookie}. Anonymous callers get the JSON 401 envelope.
   */
  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal Long userId) {
    auth.logout(userId);
    return ResponseEntity.status(HttpStatus.OK)
        .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
        .body(ApiResponse.ok(null));
  }

  /** Cookie-clearing counterpart of {@link #refreshCookie}: identical name,
   * path and SameSite so the browser overwrites it; Max-Age=0 expires it. */
  private ResponseCookie clearedRefreshCookie() {
    return ResponseCookie.from(REFRESH_COOKIE, "")
        .httpOnly(true)
        // Local dev runs plain HTTP; production behind HTTPS must set Secure.
        .secure(false)
        .path("/")
        .sameSite("Lax")
        .maxAge(Duration.ZERO)
        .build();
  }

  private ResponseEntity<ApiResponse<AuthResponse>> ok(AuthService.AuthResult result) {
    AuthResponse body = AuthResponse.of(result.user(), result.pair(),
        jwtProps.getAccessTtl().toSeconds());
    return ResponseEntity.status(HttpStatus.OK)
        .header(HttpHeaders.SET_COOKIE, refreshCookie(result.pair().refreshToken()).toString())
        .body(ApiResponse.ok(body));
  }

  private ResponseCookie refreshCookie(String token) {
    Duration maxAge = jwtProps.getRefreshTtl();
    return ResponseCookie.from(REFRESH_COOKIE, token)
        .httpOnly(true)
        // Local dev runs plain HTTP; production behind HTTPS must set Secure.
        .secure(false)
        .path("/")
        .sameSite("Lax")
        .maxAge(maxAge)
        .build();
  }
}
