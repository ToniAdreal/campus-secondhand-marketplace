package com.toni.marketplace.auth;

/**
 * Result of {@code POST /api/auth/sessions/revoke-others} (backlog #163):
 * how many of the caller's OTHER refresh-token families were revoked. The
 * current session is never part of the count — it survives by construction.
 */
public record RevokeOtherSessionsResponse(int revokedCount) {}
