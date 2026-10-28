package com.toni.marketplace.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/reviews/{id}/response (backlog #246). The response
 * is required text, length-capped here at the boundary (400 on
 * violation) to match the VARCHAR(1000) column — the same bound the
 * #240 dispute note uses. A blank response is rejected rather than
 * clearing the reply: clearing is not in the #246 scope, and a blank
 * write must never silently erase a published reply. The service
 * trims what passes.
 */
public record ReviewResponseRequest(
    @NotBlank @Size(max = 1000) String response) {
}
