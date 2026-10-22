package com.toni.marketplace.notification;

import jakarta.validation.constraints.NotNull;

/**
 * Settings toggle payload for one notification type (backlog #216).
 * {@code enabled} is required: an absent flag is a malformed toggle
 * (400), never a silent default in either direction.
 */
public record NotificationPreferenceUpdateRequest(
    @NotNull(message = "enabled is required")
    Boolean enabled) {}
