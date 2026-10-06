package com.toni.marketplace.common;

/** Single response envelope: every API response is {code, message, data}. */
public record ApiResponse<T>(int code, String message, T data) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "ok", data);
  }

  public static <T> ApiResponse<T> fail(int code, String message) {
    return new ApiResponse<>(code, message, null);
  }
}
