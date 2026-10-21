package com.toni.marketplace.auth;

import jakarta.validation.constraints.NotBlank;

/** Email-change confirm payload (backlog #208): the token from the mailed link. */
public record EmailChangeConfirmRequest(
    @NotBlank(message = "token is required")
    String token) {}
