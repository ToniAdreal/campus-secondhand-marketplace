package com.toni.marketplace.auth;

/**
 * Step-up proof answer (backlog #218): the fresh access token minted
 * by {@code POST /api/auth/step-up}. Access-token-only — no refresh
 * token is issued or rotated, so the step-up is not a login and does
 * not create a session. The SPA swaps its in-memory Bearer token for
 * this one and retries the gated action exactly once.
 */
public record StepUpResponse(
    String accessToken,
    String tokenType,
    long expiresInSeconds) {

  public static StepUpResponse of(String accessToken, long accessTtlSeconds) {
    return new StepUpResponse(accessToken, "Bearer", accessTtlSeconds);
  }
}
