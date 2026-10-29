package com.toni.marketplace.auth;

import com.toni.marketplace.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

  /** The trusted-device cookie (backlog #199): its own name, never the refresh cookie. */
  private static final String TRUSTED_COOKIE = "trusted_device";

  private final AuthService auth;
  private final PasswordResetService passwordReset;
  private final EmailVerificationService emailVerification;
  private final JwtProperties jwtProps;
  private final RefreshCookieProperties cookieProps;
  private final TrustedProxiesProperties trustedProxies;
  private final TrustedDeviceProperties trustedProps;

  public AuthController(AuthService auth, PasswordResetService passwordReset,
                        EmailVerificationService emailVerification,
                        JwtProperties jwtProps, RefreshCookieProperties cookieProps,
                        TrustedProxiesProperties trustedProxies,
                        TrustedDeviceProperties trustedProps) {
    this.auth = auth;
    this.passwordReset = passwordReset;
    this.emailVerification = emailVerification;
    this.jwtProps = jwtProps;
    this.cookieProps = cookieProps;
    this.trustedProxies = trustedProxies;
    this.trustedProps = trustedProps;
  }

  /**
   * Registers a new account. While the email-verification gate is on
   * (backlog #138, the default), the answer is 201 with a
   * {@link RegisterResponse} and NO session — no access token in the body,
   * no refresh cookie — until the address is confirmed at
   * {@code POST /api/auth/email-verification/confirm}. With the gate off,
   * the answer is the pre-#138 200 with the first token pair.
   *
   * <p>OpenAPI typing (backlog #271): the method returns
   * {@code ResponseEntity<?>} because the two success bodies differ, so
   * springdoc cannot infer either from the signature. The 200 reuses the
   * {@code ApiResponseAuthResponse} component (the same envelope the
   * refresh endpoint's typed return generates); the 201 carries an inline
   * envelope whose {@code data} is the {@link RegisterResponse} — no
   * {@code ApiResponseRegisterResponse} component exists anywhere else
   * to reference. The api-drift gate pins both variants by status.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "account created; email verification pending — no session issued (envelope)", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "object"), schemaProperties = {@io.swagger.v3.oas.annotations.media.SchemaProperty(name = "code", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "integer", description = "0 on success; the HTTP status code on failure")), @io.swagger.v3.oas.annotations.media.SchemaProperty(name = "message", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", description = "\"ok\" on success; a human-readable reason on failure")), @io.swagger.v3.oas.annotations.media.SchemaProperty(name = "data", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = RegisterResponse.class, description = "Response payload; null on failure"))})), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "account created and session issued (verification gate off) (envelope)", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "#/components/schemas/ApiResponseAuthResponse"))), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "validation failure or weak password (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "username or email already taken (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/register")
  public ResponseEntity<?> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    AuthService.RegisterResult result = auth.register(request.username(), request.email(),
        request.password(), SessionMeta.of(http));
    if (result.emailVerificationRequired()) {
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(ApiResponse.ok(RegisterResponse.pendingVerification(result.user())));
    }
    return ok(new AuthService.AuthResult(result.user(), result.pair()));
  }

  /**
   * Confirms an email address (backlog #138, public — the token IS the
   * credential): consumes the single-use token and flips the account's
   * verified flag. Unknown, used and expired tokens → the identical 400.
   * No session is issued here; the user logs in with their password
   * afterwards.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "email address verified; log in with the account password (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "unknown, used or expired token — identical for every token failure (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/email-verification/confirm")
  public ResponseEntity<ApiResponse<Void>> confirmEmailVerification(
      @Valid @RequestBody EmailVerificationConfirmRequest request) {
    emailVerification.confirm(request.token());
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Re-sends a verification token (backlog #138, public): the answer is
   * ALWAYS this identical 200 envelope, whether or not the address
   * belongs to an unverified account — no enumeration oracle. When it
   * does, a fresh single-use token (24 h, hash-only storage) replaces any
   * earlier one and is delivered through the mail seam; it never appears
   * in any response.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "always this identical envelope, account or not — no enumeration oracle"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/email-verification/resend")
  public ResponseEntity<ApiResponse<Void>> resendEmailVerification(
      @Valid @RequestBody EmailVerificationResendRequest request) {
    emailVerification.resend(request.email());
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Password login. For a second-factor account the password check alone
   * does not finish the login: the answer is HTTP 202 with a short-lived
   * signed challenge (no token pair, no refresh cookie), which the client
   * exchanges — with the 6-digit TOTP code, a recovery code, or a passkey
   * assertion (backlog #220) — at {@code POST /api/auth/2fa/authenticate}.
   * The 202 body advertises which factors the account holds
   * ({@code "totp"} and/or {@code "passkey"}) so the SPA renders exactly
   * the options that can succeed.
   *
   * <p>OpenAPI typing (backlog #271): same {@code ResponseEntity<?>}
   * situation as register — the 200 reuses the
   * {@code ApiResponseAuthResponse} component, and the 202 carries an
   * inline envelope whose {@code data} is the
   * {@link TotpChallengeResponse} (no envelope component for it exists
   * elsewhere). The api-drift gate pins both variants by status.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "token pair issued (refresh token in the httpOnly cookie)", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "#/components/schemas/ApiResponseAuthResponse"))), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "second factor enrolled: exchange the signed challenge at /api/auth/2fa/authenticate (factors lists totp and/or passkey)", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "object"), schemaProperties = {@io.swagger.v3.oas.annotations.media.SchemaProperty(name = "code", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "integer", description = "0 on success; the HTTP status code on failure")), @io.swagger.v3.oas.annotations.media.SchemaProperty(name = "message", schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", description = "\"ok\" on success; a human-readable reason on failure")), @io.swagger.v3.oas.annotations.media.SchemaProperty(name = "data", schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = TotpChallengeResponse.class, description = "Response payload; null on failure"))})), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "invalid credentials — identical for unknown identifiers (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "credentials correct but the email address is not verified yet (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "account locked after repeated failures — see Retry-After (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/login")
  public ResponseEntity<?> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http,
      @CookieValue(value = TRUSTED_COOKIE, required = false) String trustedToken) {
    // Backlog #171: this meta feeds both the login-history row and the
    // new session's refresh-token row, so both capture the client IP
    // resolved through the #104 trusted-proxy set (with the default
    // empty set that is exactly the remote address, as before).
    // Backlog #199: the trusted_device cookie rides along — for a
    // TOTP-enabled account a live trust row skips only the challenge
    // (the password was still verified); ok() then replaces the cookie
    // with the rotated token.
    AuthService.LoginResult result = auth.login(request.usernameOrEmail(), request.password(),
        SessionMeta.of(http, trustedProxies.trustedProxySet()), trustedToken);
    if (result instanceof AuthService.LoginResult.Pair pair) {
      return ok(pair.result());
    }
    AuthService.LoginResult.Challenge challenge = (AuthService.LoginResult.Challenge) result;
    ResponseEntity.BodyBuilder challengeResponse = ResponseEntity.status(HttpStatus.ACCEPTED);
    if (trustedToken != null && !trustedToken.isBlank()) {
      // Trust did not carry this login (unknown / expired / replayed /
      // another account's token): the presented cookie is dead weight
      // for this account — clear it so the browser stops offering it.
      challengeResponse.header(HttpHeaders.SET_COOKIE, clearedTrustedCookie().toString());
    }
    return challengeResponse
        .body(ApiResponse.ok(
            new TotpChallengeResponse(challenge.challengeToken(), challenge.expiresAt(),
                challenge.factors())));
  }

  /**
   * Step-up re-authentication (backlog #218, authenticated —
   * SecurityConfig): the re-proof path for the sensitive set. The
   * caller proves the current password (plus a TOTP or recovery code
   * when 2FA is enabled — the {@code DataExportConfirmRequest} proof
   * shape, shared with the export confirm) and receives a FRESH access
   * token (new {@code auth_time} claim) to retry the gated action with.
   * Access-token-only: no refresh token is issued or rotated, so this
   * is not a login and creates no session. Wrong password or wrong
   * code → the identical 401; failed code proofs feed the shared TOTP
   * lockout (an active lock answers 423). Own per-user rate-limit
   * bucket in AuthRateLimitFilter (a wrong-password guesser must not
   * get unlimited tries).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "proof accepted; fresh access token issued (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "wrong password or wrong/missing second-factor code — identical for both (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "second-factor attempts locked after repeated failures — see Retry-After (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/step-up")
  public ResponseEntity<ApiResponse<StepUpResponse>> stepUp(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody DataExportConfirmRequest request) {
    String accessToken = auth.stepUp(userId, request.currentPassword(), request.code());
    return ResponseEntity.ok(
        ApiResponse.ok(StepUpResponse.of(accessToken, jwtProps.getAccessTtl().toSeconds())));
  }

  /**
   * Starts TOTP enrollment for the caller (authenticated): returns the
   * Base32 shared secret and the otpauth:// URI to scan into an
   * authenticator app. Re-running setup regenerates the secret and resets
   * enrollment — complete {@code /2fa/enable} again afterwards.
   * Backlog #250: issuing the secret is step-up gated — the presented
   * access token must carry a fresh credential proof, like passkey
   * registration; a stale token answers the dedicated 403 step-up
   * envelope and mints nothing, so a stolen session cannot enroll its
   * own authenticator. Re-prove at {@code /api/auth/step-up} and retry.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "shared secret + otpauth URI (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "credential proof older than the step-up window — re-prove at /api/auth/step-up (envelope)")})
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
   * Turns TOTP 2FA off for the caller (backlog #121, authenticated) — the
   * reverse of {@code /2fa/enable}. Because disabling strips a security
   * factor, the request must prove both factors: the current password AND
   * a valid 6-digit TOTP code or an unused recovery code (consumed by the
   * disable). Wrong password or wrong code → the identical 401; failed
   * second-factor attempts feed the #101 lockout (an active lock answers
   * 423); 2FA not enabled → 422. On success the secret is cleared, every
   * recovery code is deleted, and the current session survives.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "two-factor authentication turned off (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "wrong password or wrong code — identical for both (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "two-factor authentication is not enabled (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "second-factor attempts locked after repeated failures — see Retry-After (envelope)")})
  @PostMapping("/2fa/disable")
  public ResponseEntity<ApiResponse<Void>> disableTotp(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody TotpDisableRequest request) {
    auth.disableTotp(userId, request.currentPassword(), request.code());
    // Backlog #199: disabling retired every trust row; clear the cookie.
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, clearedTrustedCookie().toString())
        .body(ApiResponse.ok(null));
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
   * Regenerates the caller's recovery-code set without re-enrolling
   * (backlog #127, authenticated) — the light-weight answer to "I used
   * or lost most of my codes": the TOTP secret and the authenticator
   * enrollment stay untouched (re-running setup/enable would rotate
   * them). Because a fresh set is itself a credential, the request must
   * prove both factors, exactly like {@code /2fa/disable}: the current
   * password AND a valid TOTP code or an unused recovery code (consumed
   * by the regeneration — the whole old set is deleted on success).
   * Wrong password or wrong code → the identical 401; failed code
   * attempts feed the shared TOTP lockout (an active lock answers 423);
   * 2FA not enabled → 422. The response carries the 10 new codes —
   * shown once, only their hashes are stored.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "fresh recovery-code set issued — shown once, only hashes stored (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "wrong password or wrong code — identical for both (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "two-factor authentication is not enabled (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "second-factor attempts locked after repeated failures — see Retry-After (envelope)")})
  @PostMapping("/2fa/recovery-codes/regenerate")
  public ResponseEntity<ApiResponse<TotpEnableResponse>> regenerateRecoveryCodes(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody TotpRecoveryCodeRegenerateRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(new TotpEnableResponse(
        auth.regenerateRecoveryCodes(userId, request.currentPassword(), request.code()))));
  }

  /**
   * Starts passkey enrolment (backlog #220, authenticated —
   * SecurityConfig): mints the registration ceremony options — the
   * single-use server challenge plus the rp/user descriptors — for
   * {@code navigator.credentials.create()}. The presented access token
   * must carry a FRESH credential proof (backlog #218 step-up): a
   * merely-authenticated session must not be enough to enroll a new
   * second factor. Re-running options replaces the previous unused
   * challenge (the #90 one-live-token discipline).
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "registration ceremony options (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "credential proof older than the step-up window — re-prove at /api/auth/step-up (envelope)")})
  @PostMapping("/2fa/passkeys/registration/options")
  public ResponseEntity<ApiResponse<PasskeyRegistrationOptions>> startPasskeyRegistration(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok(ApiResponse.ok(auth.startPasskeyRegistration(userId)));
  }

  /**
   * Completes passkey enrolment (backlog #220, authenticated —
   * SecurityConfig): verifies the {@code navigator.credentials.create()}
   * response against the stored single-use challenge and persists the
   * credential (id, COSE public key, signature counter). The request
   * carries the raw {@code PublicKeyCredential} JSON — byte fields
   * base64url-encoded per the WebAuthn spec. Wrong/expired/used
   * challenge or a failed ceremony → the identical 401; no live
   * challenge → 400 (request options first); an already-enrolled
   * credential id → 409. A {@code PASSKEY_REGISTERED} audit row and a
   * security notification are recorded in the same transaction.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "passkey enrolled; the credential id is in the envelope"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "no live registration challenge — request options first (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller, or wrong/expired/used challenge / failed ceremony — identical for every failure kind (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "credential proof older than the step-up window — re-prove at /api/auth/step-up (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "this passkey is already enrolled (envelope)")})
  @PostMapping("/2fa/passkeys/registration/verify")
  public ResponseEntity<ApiResponse<PasskeyRegistrationResult>> finishPasskeyRegistration(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody PasskeyRegistrationRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(
        new PasskeyRegistrationResult(
            auth.finishPasskeyRegistration(userId, request.credential(), request.name()))));
  }

  /**
   * Lists the caller's enrolled passkeys (backlog #220, authenticated —
   * SecurityConfig): id, credential id, enrolment instant and last
   * verified signature counter per credential. The public keys are
   * never exposed.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "the caller's enrolled passkeys (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)")})
  @GetMapping("/2fa/passkeys")
  public ResponseEntity<ApiResponse<List<PasskeyInfo>>> listPasskeys(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok(ApiResponse.ok(auth.listPasskeys(userId)));
  }

  /**
   * Removes one of the caller's passkeys (backlog #220, authenticated —
   * SecurityConfig): the presented access token must carry a FRESH
   * credential proof (backlog #218 step-up), like enrolment. Unknown
   * ids — or ids belonging to another user — are 404 (no cross-user
   * oracle), mirroring {@code DELETE /api/auth/sessions/{id}}.
   * Removing the last passkey drops the account to its remaining
   * factors — an explicit, audited choice. A
   * {@code PASSKEY_REMOVED} audit row and a security notification are
   * recorded in the same transaction.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "passkey removed (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "credential proof older than the step-up window — re-prove at /api/auth/step-up (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown id, or another user's passkey (envelope)")})
  @DeleteMapping("/2fa/passkeys/{id}")
  public ResponseEntity<ApiResponse<Void>> deletePasskey(
      @AuthenticationPrincipal Long userId,
      @PathVariable("id") long passkeyId) {
    auth.deletePasskey(userId, passkeyId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Renames one of the caller's enrolled passkeys (backlog #241,
   * authenticated — SecurityConfig): the display-only friendly name.
   * The presented access token must carry a FRESH credential proof
   * (backlog #218 step-up), like enrolment and removal — renaming
   * must not let a merely-authenticated session relabel keys into a
   * deletion mistake. A blank name clears the label (the list then
   * renders the enrolment-date label); an overlong name is a 400.
   * Unknown ids — or ids belonging to another user — are 404 (no
   * cross-user oracle), mirroring the delete endpoint.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "passkey renamed; the updated row (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "name over 100 characters (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "credential proof older than the step-up window — re-prove at /api/auth/step-up (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown id, or another user's passkey (envelope)")})
  @PatchMapping("/2fa/passkeys/{id}")
  public ResponseEntity<ApiResponse<PasskeyInfo>> renamePasskey(
      @AuthenticationPrincipal Long userId,
      @PathVariable("id") long passkeyId,
      @Valid @RequestBody PasskeyRenameRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(auth.renamePasskey(userId, passkeyId, request.name())));
  }

  /**
   * Opens a passkey assertion ceremony (backlog #220, public — the
   * caller is not authenticated yet): the 202 login challenge token
   * resolves to the challenged account, which must hold at least one
   * passkey. Returns the single-use assertion challenge plus the
   * {@code allowCredentials} list for
   * {@code navigator.credentials.get()}; the resulting assertion is
   * exchanged — with the same 202 challenge token — at
   * {@code POST /api/auth/2fa/authenticate}. Bad/expired challenge
   * token or no passkeys enrolled → the identical 401.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "assertion ceremony options (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "validation failure — missing challenge (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "bad/expired 202 challenge or no passkeys enrolled — identical for every failure kind (envelope)")})
  @PostMapping("/2fa/passkeys/assertion/options")
  public ResponseEntity<ApiResponse<PasskeyAssertionOptions>> startPasskeyAssertion(
      @Valid @RequestBody PasskeyAssertionOptionsRequest request) {
    return ResponseEntity.ok(ApiResponse.ok(auth.startPasskeyAssertion(request.challenge())));
  }

  /**
   * The second-factor exchange (public — the caller is not authenticated
   * yet): the 202 challenge plus exactly one proof — the current 6-digit
   * code, a one-time recovery code (backlog #91, consumed by a successful
   * exchange), or a WebAuthn passkey assertion (backlog #220, verified
   * against the single-use assertion challenge). On success the response
   * is the normal token pair with the httpOnly refresh cookie, exactly
   * like a finished login. Bad/expired challenge or wrong proof → the
   * identical 401, whichever factor was tried. Brute-force defences
   * (backlog #101): its own 5/min-per-IP rate-limit bucket (429), and
   * repeated failed exchanges lock the exchange for the account
   * (423 + Retry-After) — a lock that never blocks password login
   * itself. The passkey path shares both defences.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "token pair issued (refresh token in the httpOnly cookie)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "bad/expired challenge or wrong proof — identical for every failure kind and factor (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "second-factor exchange locked after repeated failures — see Retry-After (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/2fa/authenticate")
  public ResponseEntity<ApiResponse<AuthResponse>> authenticateTotp(
      @Valid @RequestBody TotpAuthenticateRequest request, HttpServletRequest http) {
    // Backlog #171: same trusted-proxy capture as the password stage, so
    // the second-factor rows carry the resolved client IP too.
    // Backlog #199: rememberDevice is the trust opt-in — on success
    // ok() sets the trusted_device cookie from the result's raw token.
    return ok(auth.authenticateSecondFactor(request.challenge(), request.code(),
        request.assertion(),
        SessionMeta.of(http, trustedProxies.trustedProxySet()),
        request.rememberDeviceOrFalse()));
  }

  /**
   * Starts a password reset (backlog #90, public like login): the answer
   * is ALWAYS this identical 200 envelope, whether or not the identifier
   * belongs to an account — no enumeration oracle. When the account
   * exists, a single-use token (30 min, hash-only storage) is delivered
   * through the mail seam; it never appears in any response.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "always this identical envelope, account or not — no enumeration oracle"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
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
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "password reset; every pre-reset session is revoked (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "weak new password — the token stays usable (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unknown, used or expired token — identical for every token failure (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
  @PostMapping("/password-reset/confirm")
  public ResponseEntity<ApiResponse<Void>> confirmPasswordReset(
      @Valid @RequestBody PasswordResetConfirmRequest request) {
    passwordReset.confirmReset(request.token(), request.newPassword());
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "rotated token pair issued (refresh token in the httpOnly cookie)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "missing, invalid, revoked or replayed refresh token (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded — see Retry-After (envelope)")})
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
   * Closes the caller's own account (backlog #158, authenticated —
   * SecurityConfig). The current password proves possession, and a
   * TOTP-enabled account must also present a valid TOTP or recovery
   * code in {@code code}; every proof failure is the identical 401.
   * On success every session is revoked, the caller's AVAILABLE
   * listings are withdrawn, and the user row is anonymized to a
   * {@code deleted-<id>} tombstone (order history is never rewritten) —
   * see {@code AuthService.closeAccount}. No session is issued here:
   * the answer is the plain 200 envelope, and the httpOnly refresh
   * cookie is cleared exactly like logout's.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "account closed; every session revoked, AVAILABLE listings withdrawn, user row anonymized (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "validation failure — missing current password (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "wrong password or wrong/missing second-factor code — identical for both (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423", description = "second-factor attempts locked after repeated failures — see Retry-After (envelope)")})
  @DeleteMapping("/me")
  public ResponseEntity<ApiResponse<Void>> closeAccount(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody AccountClosureRequest request) {
    auth.closeAccount(userId, request.currentPassword(), request.code());
    return ResponseEntity.status(HttpStatus.OK)
        .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
        .header(HttpHeaders.SET_COOKIE, clearedTrustedCookie().toString())
        .body(ApiResponse.ok(null));
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
    ResponseEntity<ApiResponse<AuthResponse>> response = ok(auth.changePassword(
        userId, request.currentPassword(), request.newPassword(), SessionMeta.of(http)));
    // Backlog #199: the change revoked every trusted device server-side;
    // clear this browser's now-dead trusted cookie too.
    return ResponseEntity.status(response.getStatusCode())
        .headers(headers -> {
          headers.addAll(response.getHeaders());
          headers.add(HttpHeaders.SET_COOKIE, clearedTrustedCookie().toString());
        })
        .body(response.getBody());
  }

  /**
   * Changes the caller's username (backlog #256, authenticated —
   * SecurityConfig). The format rules mirror registration (3–60
   * characters, stored canonical-lowercase); a name held by another
   * account — or the caller's own current name, any case — is a 409.
   * The change is immediate (no staged/confirm step, unlike email):
   * seller profiles, order parties and receipts resolve usernames from
   * user rows at read time, so they follow the new name at once, and
   * the freed name returns to the pool immediately. Sessions are
   * untouched — no tokenVersion bump, no refresh revocation — so the
   * caller stays signed in; the response's user summary already
   * carries the new name (an already-issued access token's username
   * claim goes stale until it expires or rotates, ≤15 min). A
   * USERNAME_CHANGED audit row is written in the same transaction.
   * Freshness gate (backlog #261): the presented token must carry a
   * credential proof within app.auth.step-up.max-age — a stale token
   * answers the dedicated 403 step-up envelope, changes nothing and
   * writes no audit row; the SPA re-proves via the step-up modal and
   * retries exactly once.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "username changed; the updated user summary (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "validation failure — blank or outside 3-60 characters (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "stale credential proof — dedicated step-up envelope, nothing changed (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "username already taken, or equal to the current username (envelope)")})
  @PostMapping("/username")
  public ResponseEntity<ApiResponse<AuthResponse.UserSummary>> changeUsername(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody UsernameChangeRequest request) {
    return ResponseEntity.ok(
        ApiResponse.ok(AuthResponse.UserSummary.of(auth.changeUsername(userId, request.username()))));
  }

  /**
   * Requests an email change (backlog #150, reworked by #208,
   * authenticated \u2014 SecurityConfig). The current password proves
   * possession (wrong \u2192 the identical 401); a taken address \u2014 or
   * the caller's own current one \u2014 is a 409, checked again at
   * confirm time. On success NOTHING switches yet: the new address is
   * staged as pending, a single-use token is mailed to the NEW
   * address, the holder is notified in-app at the old one, and every
   * session (this one included) stays alive \u2014 the answer is the
   * plain 200 envelope and no cookie is cleared. The switch happens
   * only at {@code POST /api/auth/email-change/confirm}.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "change staged as pending; a single-use token was mailed to the new address \u2014 the switch happens at confirm (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "validation failure \u2014 malformed new address (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "wrong current password \u2014 identical to a bad login (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "new address already registered, or equal to the current address (envelope)")})
  @PostMapping("/email-change")
  public ResponseEntity<ApiResponse<Void>> changeEmail(
      @AuthenticationPrincipal Long userId,
      @Valid @RequestBody EmailChangeRequest request) {
    auth.changeEmail(userId, request.currentPassword(), request.newEmail());
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Confirms a pending email change (backlog #208, public \u2014 the
   * token IS the credential): consumes the single-use token mailed to
   * the new address and performs the switch \u2014 the address flips,
   * the account is verified for it, every pre-switch session is
   * revoked and tokenVersion bumped, and an EMAIL_CHANGED audit row
   * is written. Unknown, used and expired tokens \u2192 the identical
   * 401; an address taken between request and confirm \u2192 409 (the
   * token is not consumed). No session is issued here: the holder
   * logs in at the new address afterwards, and the httpOnly refresh
   * cookie is cleared exactly like logout's because the session it
   * belonged to was just revoked.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "email changed; every pre-switch session revoked (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "validation failure \u2014 missing token (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "unknown, used or expired token \u2014 identical for every token failure (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "the pending address was registered by another account between request and confirm (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "rate limit exceeded \u2014 see Retry-After (envelope)")})
  @PostMapping("/email-change/confirm")
  public ResponseEntity<ApiResponse<Void>> confirmEmailChange(
      @Valid @RequestBody EmailChangeConfirmRequest request) {
    auth.confirmEmailChange(request.token());
    return ResponseEntity.status(HttpStatus.OK)
        .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
        .body(ApiResponse.ok(null));
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
   * The caller's own login history (backlog #171, authenticated —
   * SecurityConfig): every recorded login outcome for the caller's
   * account — successes, bad passwords, lockouts, TOTP stages and the
   * #138 unverified refusal — newest first, paginated (default page
   * size 20, sorted by {@code createdAt} desc with {@code id} desc as
   * the tie-break; the #75 global cap clamps oversized {@code size}).
   * Rows recorded against unknown identifiers belong to no account and
   * are never returned here. Rows are {@link LoginEventDto} projections
   * — never the entity.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "the caller's own login history, newest first (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)")})
  @GetMapping("/login-history")
  public ResponseEntity<ApiResponse<Page<LoginEventDto>>> loginHistory(
      @AuthenticationPrincipal Long userId,
      @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
      Pageable pageable) {
    return ResponseEntity.ok(ApiResponse.ok(auth.loginHistory(userId, pageable)));
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
   * Revokes every session of the caller EXCEPT the current one (backlog
   * #163, authenticated): the "sign out everywhere else" counterpart of
   * the one-at-a-time {@code DELETE /api/auth/sessions/{id}}. The current
   * session is the family behind the presented {@code refresh_token}
   * cookie — identified by the cookie only, never by a client-supplied
   * id. The response reports how many families were revoked. Unlike
   * {@code POST /api/auth/logout} this does NOT bump tokenVersion, so the
   * current session's access token keeps working; the revoked sessions'
   * already-issued access tokens live out their ≤15-minute TTL (their
   * refresh cookies 401 at once) — the honest limit stated in the README.
   * An absent or unknown cookie identifies no current family, so every
   * family is revoked.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "every other session revoked; the count is in the envelope (the current session survives)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)")})
  @PostMapping("/sessions/revoke-others")
  public ResponseEntity<ApiResponse<RevokeOtherSessionsResponse>> revokeOtherSessions(
      @AuthenticationPrincipal Long userId,
      @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
      @CookieValue(value = TRUSTED_COOKIE, required = false) String trustedToken) {
    return ResponseEntity.ok(ApiResponse.ok(
        new RevokeOtherSessionsResponse(
            auth.revokeOtherSessions(userId, refreshToken, trustedToken))));
  }

  /**
   * Lists the caller's trusted devices (backlog #199, authenticated —
   * SecurityConfig): the trust rows behind the TOTP-skip, shown on the
   * sessions surface next to the live sessions. Rows are
   * {@link TrustedDeviceDto} projections — the token and its hashes are
   * never readable back.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "the caller's trusted devices, newest first (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)")})
  @GetMapping("/trusted-devices")
  public ResponseEntity<ApiResponse<List<TrustedDeviceDto>>> trustedDevices(
      @AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok(ApiResponse.ok(auth.trustedDevices(userId)));
  }

  /**
   * Revokes one trusted device (backlog #199, authenticated): the row
   * is deleted, so that device's cookie buys nothing at its next login
   * and the full TOTP challenge applies again. Unknown ids — or ids
   * belonging to another user — are 404 (no cross-user oracle),
   * mirroring {@code DELETE /api/auth/sessions/{id}}.
   */
  @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "trusted device revoked (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "anonymous caller (envelope)"), @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "unknown id, or another user's device (envelope)")})
  @DeleteMapping("/trusted-devices/{id}")
  public ResponseEntity<ApiResponse<Void>> revokeTrustedDevice(
      @AuthenticationPrincipal Long userId,
      @PathVariable("id") long deviceId) {
    auth.revokeTrustedDevice(userId, deviceId);
    return ResponseEntity.ok(ApiResponse.ok(null));
  }

  /**
   * Logs out the caller (identified by the Bearer access token — the
   * SecurityConfig requires authentication for this route): every refresh
   * token of the user is revoked server-side, the user's tokenVersion is
   * bumped so every previously issued access token 401s immediately
   * (backlog #148), and the httpOnly
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
        // Must agree with refreshCookie's Secure flag (backlog #130) or
        // the browser will not overwrite the stored cookie.
        .secure(cookieProps.isCookieSecure())
        .path("/")
        .sameSite("Lax")
        .maxAge(Duration.ZERO)
        .build();
  }

  private ResponseEntity<ApiResponse<AuthResponse>> ok(AuthService.AuthResult result) {
    AuthResponse body = AuthResponse.of(result.user(), result.pair(),
        jwtProps.getAccessTtl().toSeconds());
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.OK)
        .header(HttpHeaders.SET_COOKIE, refreshCookie(result.pair().refreshToken()).toString());
    // Backlog #199: a result carrying a raw trusted-device token (the
    // trust-creating exchange, or the rotation on a trusted login)
    // sets the trusted_device cookie alongside the refresh cookie.
    if (result.trustedDeviceToken() != null) {
      response.header(HttpHeaders.SET_COOKIE,
          trustedCookie(result.trustedDeviceToken()).toString());
    }
    return response.body(ApiResponse.ok(body));
  }

  /**
   * The trusted-device cookie (backlog #199): httpOnly like the refresh
   * cookie, its own name and lifetime ({@code app.auth.trusted-device.ttl}).
   * The raw token exists only here and in the browser's cookie jar —
   * the server stores its SHA-256 hash.
   */
  private ResponseCookie trustedCookie(String token) {
    Duration maxAge = trustedProps != null ? trustedProps.getTtl() : Duration.ofDays(30);
    return ResponseCookie.from(TRUSTED_COOKIE, token)
        .httpOnly(true)
        .secure(cookieProps.isCookieSecure())
        .path("/")
        .sameSite("Lax")
        .maxAge(maxAge)
        .build();
  }

  /** Cookie-clearing counterpart of {@link #trustedCookie}: identical name/path/SameSite, Max-Age=0. */
  private ResponseCookie clearedTrustedCookie() {
    return ResponseCookie.from(TRUSTED_COOKIE, "")
        .httpOnly(true)
        .secure(cookieProps.isCookieSecure())
        .path("/")
        .sameSite("Lax")
        .maxAge(Duration.ZERO)
        .build();
  }

  private ResponseCookie refreshCookie(String token) {
    Duration maxAge = jwtProps.getRefreshTtl();
    return ResponseCookie.from(REFRESH_COOKIE, token)
        .httpOnly(true)
        // Secure is configured via app.auth.cookie-secure (backlog #130):
        // off for local plain-HTTP dev, on behind HTTPS — the mysql
        // profile refuses to boot with it off unless the operator sets
        // the explicit allow-insecure-cookie opt-out.
        .secure(cookieProps.isCookieSecure())
        .path("/")
        .sameSite("Lax")
        .maxAge(maxAge)
        .build();
  }
}
