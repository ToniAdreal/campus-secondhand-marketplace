package com.toni.marketplace.common;

import com.toni.marketplace.common.AccountLockedException;
import com.toni.marketplace.common.CategoryInUseException;
import com.toni.marketplace.common.DuplicateCategoryException;
import com.toni.marketplace.common.DuplicateUserException;
import com.toni.marketplace.common.InvalidCredentialsException;
import com.toni.marketplace.common.InvalidTokenException;
import com.toni.marketplace.common.PaymentDeclinedException;
import com.toni.marketplace.common.WeakPasswordException;
import com.toni.marketplace.common.InvalidImageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
    String msg = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .findFirst().orElse("validation failed");
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, msg));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.fail(400, "missing required header: " + ex.getHeaderName()));
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.fail(400, "missing required part: " + ex.getRequestPartName()));
  }

  /**
   * Query-param-bound endpoints (e.g. {@code GET /api/messages?itemId=}):
   * a missing parameter would otherwise leave the JSON envelope for the
   * container's default error page.
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<Void>> handleMissingParam(
      MissingServletRequestParameterException ex) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.fail(400, "missing required parameter: " + ex.getParameterName()));
  }

  @ExceptionHandler(InvalidImageException.class)
  public ResponseEntity<ApiResponse<Void>> handleInvalidImage(InvalidImageException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, ex.getMessage()));
  }

  /**
   * Oversized multipart requests are rejected by Spring's servlet layer
   * before the controller is reached; answer 413 in the JSON envelope rather
   * than the container's HTML error page.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ApiResponse.fail(HttpStatus.PAYLOAD_TOO_LARGE.value(), "image too large"));
  }

  /**
   * Malformed JSON request bodies (e.g. an enum-typed field carrying a
   * value that is not a valid enum constant) would otherwise surface as a
   * 500 through the generic handler — answer 400 in the JSON envelope.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest()
        .body(ApiResponse.fail(400, "malformed request body"));
  }

  /**
   * Mock-PSP capture decline (backlog #92): the buyer's payment was
   * declined, the order stays PENDING, and a retry with a different token
   * may succeed — answered {@code 402 Payment Required} in the JSON
   * envelope, the status real PSP integrations conventionally map a
   * decline to.
   */
  @ExceptionHandler(PaymentDeclinedException.class)
  public ResponseEntity<ApiResponse<Void>> handlePaymentDeclined(PaymentDeclinedException ex) {
    return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
        .body(ApiResponse.fail(HttpStatus.PAYMENT_REQUIRED.value(), ex.getMessage()));
  }

  @ExceptionHandler(WeakPasswordException.class)
  public ResponseEntity<ApiResponse<Void>> handleWeakPassword(WeakPasswordException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, ex.getMessage()));
  }

  @ExceptionHandler(PasswordReuseException.class)
  public ResponseEntity<ApiResponse<Void>> handlePasswordReuse(PasswordReuseException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, ex.getMessage()));
  }

  /**
   * TOTP 2FA (backlog #78): a well-formed code that does not verify at
   * {@code POST /api/auth/2fa/enable} (or an enable attempt with no setup
   * behind it) is 400. A wrong code at the unauthenticated
   * {@code /2fa/authenticate} step is instead {@link
   * InvalidCredentialsException} → 401.
   */
  @ExceptionHandler(InvalidTotpCodeException.class)
  public ResponseEntity<ApiResponse<Void>> handleInvalidTotpCode(InvalidTotpCodeException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, ex.getMessage()));
  }

  @ExceptionHandler({InvalidCredentialsException.class, InvalidTokenException.class})
  public ResponseEntity<ApiResponse<Void>> handleAuthFailure(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ApiResponse.fail(401, ex.getMessage()));
  }

  /**
   * Per-account login lockout (backlog #60): the account is temporarily
   * locked after {@code app.auth.login-lockout.max-attempts} consecutive
   * failed logins. Answers {@code 423 Locked} in the JSON envelope with a
   * {@code Retry-After} header (seconds until the lock expires).
   */
  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<ApiResponse<Void>> handleAccountLocked(AccountLockedException ex) {
    return ResponseEntity.status(HttpStatus.LOCKED)
        .header("Retry-After", Long.toString(ex.getRetryAfterSeconds()))
        .body(ApiResponse.fail(HttpStatus.LOCKED.value(), ex.getMessage()));
  }

  @ExceptionHandler({DuplicateUserException.class, DuplicateCategoryException.class,
      CategoryInUseException.class})
  public ResponseEntity<ApiResponse<Void>> handleConflict(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.fail(409, ex.getMessage()));
  }

  /**
   * {@code @PreAuthorize} denials are thrown by the method-security
   * interceptor inside the DispatcherServlet, so they surface here rather
   * than at the filter layer — map them to the same JSON 403 envelope.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiResponse.fail(HttpStatus.FORBIDDEN.value(), "forbidden"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> handleStatus(ResponseStatusException ex) {
    int code = ex.getStatusCode().value();
    String msg = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
    return ResponseEntity.status(code).body(ApiResponse.fail(code, msg));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail(500, "internal error"));
  }
}
