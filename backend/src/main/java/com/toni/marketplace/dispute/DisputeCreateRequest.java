package com.toni.marketplace.dispute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/orders/{id}/dispute (backlog #169). The reason is
 * free text only — v1 has no evidence upload, by design — and is
 * length-capped here at the boundary (400 on violation) so a dispute
 * row can never carry an unbounded blob; the column is VARCHAR(500)
 * to match.
 */
public record DisputeCreateRequest(
    @NotBlank @Size(max = 500) String reason) {
}
