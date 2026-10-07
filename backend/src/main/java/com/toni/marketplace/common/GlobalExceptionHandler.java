package com.toni.marketplace.common;

import com.toni.marketplace.auth.DuplicateUserException;
import com.toni.marketplace.auth.InvalidCredentialsException;
import com.toni.marketplace.auth.InvalidTokenException;
import com.toni.marketplace.auth.WeakPasswordException;
import com.toni.marketplace.item.InvalidImageException;
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

  @ExceptionHandler(WeakPasswordException.class)
  public ResponseEntity<ApiResponse<Void>> handleWeakPassword(WeakPasswordException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(400, ex.getMessage()));
  }

  @ExceptionHandler({InvalidCredentialsException.class, InvalidTokenException.class})
  public ResponseEntity<ApiResponse<Void>> handleAuthFailure(RuntimeException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ApiResponse.fail(401, ex.getMessage()));
  }

  @ExceptionHandler(DuplicateUserException.class)
  public ResponseEntity<ApiResponse<Void>> handleConflict(DuplicateUserException ex) {
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
