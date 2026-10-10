package com.toni.marketplace.common;

/**
 * Rejected image upload: empty file, unsupported content type, over the
 * configured size limit, or magic bytes that do not match the declared
 * type. Mapped to 400 with the JSON envelope by
 * {@code GlobalExceptionHandler}; oversized requests that Spring itself
 * rejects at the servlet multipart layer surface as 413 instead.
 */
public class InvalidImageException extends RuntimeException {
  public InvalidImageException(String message) {
    super(message);
  }
}
