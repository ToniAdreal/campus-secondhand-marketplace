package com.toni.marketplace.moderation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload for reporting a listing. Jakarta validation is enforced by
 * the controller via {@code @Valid}: a missing reason or an overlong
 * note is rejected with the project's 400 {@code {code,message,data}}
 * envelope before the service layer is reached; a reason outside the
 * {@link ReportReason} allowlist fails JSON binding and is answered the
 * same 400 by {@code GlobalExceptionHandler#handleNotReadable}.
 */
public record ReportCreateRequest(
    @NotNull(message = "reason must be set")
    ReportReason reason,

    @Size(max = 1000, message = "note must be at most 1000 characters")
    String note) {
}
