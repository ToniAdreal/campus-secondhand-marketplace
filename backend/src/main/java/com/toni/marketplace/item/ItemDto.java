package com.toni.marketplace.item;

import java.time.Instant;
import java.util.List;

/**
 * Public view of a listing. JPA entities never leave the service layer, so
 * controllers serialize this DTO instead — no lazy associations and no
 * internal persistence fields (e.g. the optimistic-lock {@code version})
 * leak into the JSON contract.
 */
public record ItemDto(
    Long id,
    String title,
    String description,
    Long priceCents,
    ItemStatus status,
    Long sellerId,
    Long categoryId,
    String categoryName,
    /** Public URL path of the listing photo ("/uploads/<uuid>.<ext>"), or null. */
    String photoUrl,
    Instant createdAt,
    Instant updatedAt,
    /**
     * The listing's photo gallery, ordered by position — the first entry is
     * the primary photo the client should render. Never null (empty list
     * when the listing has no gallery photos); legacy listings that only
     * carry {@link #photoUrl()} show an empty gallery.
     */
    List<ItemPhotoDto> photos) {

  /**
   * Constructor projection compatibility: the JPQL list-view projections in
   * {@link ItemRepository} cannot express a nested collection, so they call
   * this 11-arg form (photos = empty) and the service merges the real
   * galleries in one batch query afterwards.
   */
  public ItemDto(
      Long id,
      String title,
      String description,
      Long priceCents,
      ItemStatus status,
      Long sellerId,
      Long categoryId,
      String categoryName,
      String photoUrl,
      Instant createdAt,
      Instant updatedAt) {
    this(id, title, description, priceCents, status, sellerId, categoryId,
        categoryName, photoUrl, createdAt, updatedAt, List.of());
  }
}
