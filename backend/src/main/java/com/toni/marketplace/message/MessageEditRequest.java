package com.toni.marketplace.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Edit-message request (backlog #206). Same body rules as the original
 * send ({@link MessageCreateRequest}): blank or overlong replacement
 * text is the 400 envelope, never a silently stored empty edit. The
 * sender is never taken from the body — it is the JWT principal.
 */
public record MessageEditRequest(
    @NotBlank(message = "body must not be blank")
    @Size(max = Message.MAX_BODY_LENGTH, message = "body is too long (max 2000 characters)")
    String body) {
}
