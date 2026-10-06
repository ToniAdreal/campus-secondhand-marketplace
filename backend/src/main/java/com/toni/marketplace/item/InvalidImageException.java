package com.toni.marketplace.item;

/**
 * Rejected image upload: empty file, unsupported content type, or over the
 * configured size limit. Mapped to 400 with the JSON envelope by
 * {@code GlobalExceptionHandler}; oversized requests that Spring itself
 * rejects at the servlet multipart layer surface as 413 instead.
 */
public class InvalidImageException extends RuntimeException {
  public InvalidImageException(String message) {
    super(message);
  }
}
