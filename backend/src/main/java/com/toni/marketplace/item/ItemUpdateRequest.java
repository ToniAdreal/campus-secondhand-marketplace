package com.toni.marketplace.item;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload for editing a listing ({@code PATCH /api/items/{id}}). All fields
 * are optional — a {@code null} field is left untouched (partial update).
 * Present fields are validated with the same rules as
 * {@link ItemCreateRequest}.
 *
 * <p>Status on this endpoint (backlog #198): the optional {@code status}
 * field accepts only {@link ItemStatus#AVAILABLE} or
 * {@link ItemStatus#DRAFT} — moving AVAILABLE to DRAFT unpublishes the
 * listing (its expiry deadline is cleared; drafts never expire) and
 * moving DRAFT to AVAILABLE publishes it (its #177 expiry clock starts
 * at that moment, exactly as on {@code POST /api/items/{id}/publish},
 * which remains the canonical publish path). Any other requested value,
 * and any status change requested on a RESERVED/SOLD/WITHDRAWN/EXPIRED
 * listing, is answered 422 by the service layer. The SOLD transition
 * still lives only on {@code PATCH /api/items/{id}/status}.
 *
 * <p>A blank-but-present title is rejected by the service layer with 400
 * (no jakarta annotation distinguishes "absent" from "blank" on a nullable
 * field).
 */
public record ItemUpdateRequest(
    @Size(max = 200, message = "title must be at most 200 characters")
    String title,

    @Size(max = 5000, message = "description must be at most 5000 characters")
    String description,

    @Positive(message = "priceCents must be positive")
    Long priceCents,

    @Positive(message = "categoryId must be positive")
    Long categoryId,

    /** Optional draft/publish status — see the class javadoc (#198). */
    ItemStatus status) {

  /** Backward-compatible 4-arg form (status absent = leave untouched). */
  public ItemUpdateRequest(String title, String description, Long priceCents, Long categoryId) {
    this(title, description, priceCents, categoryId, null);
  }
}
