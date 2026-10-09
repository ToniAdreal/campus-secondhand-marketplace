package com.toni.marketplace.common;

/** Single response envelope: every API response is {code, message, data}. */
@io.swagger.v3.oas.annotations.media.Schema(
    description = "Single response envelope for every API response: "
        + "code is 0 on success and the HTTP status on failure; "
        + "data is null on failure.")
public record ApiResponse<T>(
    @io.swagger.v3.oas.annotations.media.Schema(
        description = "0 on success; the HTTP status code on failure",
        example = "0")
    int code,
    @io.swagger.v3.oas.annotations.media.Schema(
        description = "\"ok\" on success; a human-readable reason on failure",
        example = "ok")
    String message,
    @io.swagger.v3.oas.annotations.media.Schema(
        description = "Response payload; null on failure")
    T data) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "ok", data);
  }

  public static <T> ApiResponse<T> fail(int code, String message) {
    return new ApiResponse<>(code, message, null);
  }
}
