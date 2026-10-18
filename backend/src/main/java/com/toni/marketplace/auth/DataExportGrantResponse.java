package com.toni.marketplace.auth;

import java.time.Instant;

/**
 * The minted data-export grant (backlog #188), returned exactly once
 * by {@code POST /api/users/me/export/confirm}. The raw {@code grant}
 * is a bearer <redacted> for one download: the SPA presents it as
 * the {@code X-Export-Grant} header on
 * {@code GET /api/users/me/export} within {@code expiresAt}; only its
 * SHA-256 hash is stored server-side.
 */
public record DataExportGrantResponse(String grant, Instant expiresAt) {}
