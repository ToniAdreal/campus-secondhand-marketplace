package com.toni.marketplace.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating a listing. Jakarta validation is enforced by the
 * controller via {@code @Valid}: a blank title or a non-positive price is
 * rejected with the project's 400 {@code {code,message,data}} envelope
 * before the service layer is reached.
 */
public record ItemCreateRequest(
    @NotBlank(message = "title must not be blank")
    @Size(max = 200, message = "title must be at most 200 characters")
    String title,

    @Size(max = 5000, message = "description must be at most 5000 characters")
    String description,

    @NotNull(message = "priceCents must be set")
    @Positive(message = "priceCents must be positive")
    Long priceCents,

    /**
     * Optional category id (see the {@code category} taxonomy seeded by
     * Flyway V3). {@code @Positive} only constrains non-null values; a
     * missing category id leaves the listing uncategorized.
     */
    @Positive(message = "categoryId must be positive")
    Long categoryId,

    /**
     * Optional initial status (backlog #198): absent or {@code null}
     * keeps the historical behaviour — the listing is created
     * {@link ItemStatus#AVAILABLE} and its #177 expiry clock starts at
     * creation. {@link ItemStatus#DRAFT} creates a seller-private draft
     * with no expiry deadline (the clock starts at publish). Any other
     * value is answered 422 by the service layer — creation cannot mint
     * a RESERVED/SOLD/WITHDRAWN/EXPIRED listing.
     */
    ItemStatus status) {

  /** Backward-compatible 4-arg form (status absent = AVAILABLE). */
  public ItemCreateRequest(String title, String description, Long priceCents, Long categoryId) {
    this(title, description, priceCents, categoryId, null);
  }
}
