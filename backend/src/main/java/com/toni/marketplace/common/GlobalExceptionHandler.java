package com.toni.marketplace.common;

import com.toni.marketplace.auth.DuplicateUserException;
import com.toni.marketplace.auth.InvalidCredentialsException;
import com.toni.marketplace.auth.InvalidTokenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
