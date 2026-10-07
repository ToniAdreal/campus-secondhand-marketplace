package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
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
@RequestMapping("/api/auth")
public class AuthController {

  private static final String REFRESH_COOKIE = "refresh_token";

  private final AuthService auth;
  private final JwtProperties jwtProps;

  public AuthController(AuthService auth, JwtProperties jwtProps) {
    this.auth = auth;
    this.jwtProps = jwtProps;
  }

  @PostMapping("/register")
  public ResponseEntity<ApiResponse<AuthResponse>> register(
      @Valid @RequestBody RegisterRequest request) {
    return ok(auth.register(request.username(), request.email(), request.password()));
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<AuthResponse>> login(
      @Valid @RequestBody LoginRequest request) {
    return ok(auth.login(request.usernameOrEmail(), request.password()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<AuthResponse>> refresh(
      @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new InvalidRefreshTokenException("missing refresh token");
    }
    return ok(auth.refresh(refreshToken));
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
      @Valid @RequestBody PasswordChangeRequest request) {
    return ok(auth.changePassword(userId, request.currentPassword(), request.newPassword()));
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
