package com.toni.marketplace.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Send-message request. The sender is never taken from the body — it is the
 * JWT principal, so a client cannot impersonate another user.
 */
public record MessageCreateRequest(
    @NotNull(message = "itemId is required") Long itemId,
    @NotNull(message = "receiverId is required") Long receiverId,
    @NotBlank(message = "body must not be blank")
    @Size(max = Message.MAX_BODY_LENGTH, message = "body is too long (max 2000 characters)")
    String body) {
}
