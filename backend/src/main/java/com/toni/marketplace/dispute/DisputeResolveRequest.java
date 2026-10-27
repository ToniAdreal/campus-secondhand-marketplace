package com.toni.marketplace.dispute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/admin/disputes/{id}/resolve-refund and
 * /resolve-release (backlog #240). The ADMIN's resolution note is
 * required on every resolution call — blank or absent → 400,
 * over 1000 chars → 400 — so a verdict is never recorded without
 * its why. The note is stored on the dispute row (Flyway V70
 * {@code resolution_note}) and shown to both parties on the dispute
 * detail view and on the ADMIN queue.
 *
 * <p>The note is validated at the request boundary, before the
 * idempotent re-resolve return — mirroring #239's direct-refund
 * reason, a malformed request is malformed even when the dispute
 * was already resolved that way. A re-resolve that passes
 * validation still does not rewrite the stored note: the first
 * resolution's note stands (see {@link OrderDispute}).
 */
public record DisputeResolveRequest(
    @NotBlank @Size(max = 1000) String resolutionNote) {
}
