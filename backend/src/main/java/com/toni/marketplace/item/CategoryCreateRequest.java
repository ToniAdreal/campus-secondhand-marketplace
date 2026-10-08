package com.toni.marketplace.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/categories} (ADMIN only, backlog #70).
 *
 * <p>{@code name} keeps its display case ("Vintage &amp; Retro") — the
 * case-insensitive uniqueness contract is enforced on the canonical
 * lowercase form. {@code slug} is optional: when omitted it is derived
 * from the name; when given it is lowercased and must already look like a
 * slug, otherwise the request is rejected with 400.
 */
public record CategoryCreateRequest(
    @NotBlank(message = "name is required")
    @Size(max = 60, message = "name must be at most 60 characters")
    String name,

    @Size(max = 60, message = "slug must be at most 60 characters")
    String slug) {}
