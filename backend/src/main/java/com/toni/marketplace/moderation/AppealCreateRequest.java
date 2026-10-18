package com.toni.marketplace.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/seller/reports/{id}/appeal (backlog #190). The
 * reason is free text only — v1 has no evidence upload, by design —
 * and is length-capped here at the boundary (400 on violation) so an
 * appeal row can never carry an unbounded blob; the column is
 * VARCHAR(500) to match.
 */
public record AppealCreateRequest(
    @NotBlank @Size(max = 500) String reason) {
}
