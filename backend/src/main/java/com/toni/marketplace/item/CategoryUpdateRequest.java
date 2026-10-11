package com.toni.marketplace.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code PATCH /api/categories/{id}} (ADMIN only, backlog
 * #132) — a category rename. {@code name} is required and follows the
 * same rules as create (display case kept, uniqueness case-insensitive).
 * {@code slug} is optional with deliberately different semantics from
 * create: when omitted (or blank) the category keeps its current slug,
 * so renaming the display name never silently changes the slug sellers
 * and links already know; when given it is lowercased, must look like a
 * slug, and must not collide with another category.
 */
public record CategoryUpdateRequest(
    @NotBlank(message = "name is required")
    @Size(max = 60, message = "name must be at most 60 characters")
    String name,

    @Size(max = 60, message = "slug must be at most 60 characters")
    String slug) {}
