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
    /**
     * The seller's username, resolved by the read projections via an ad-hoc
     * JPQL join on the raw {@code sellerId} FK (the item has no JPA
     * association to the user row). Null on DTOs built from the entity alone
     * (mutation responses — the mapper has no user data) and when the seller
     * row no longer exists. Deliberate trade-off: the seller's username is
     * visible to anyone who can read the listing, like on any marketplace;
     * registration already confirms username existence via its 409
     * duplicate check.
     */
    String sellerUsername,
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
   * Legacy 11-arg form without the seller username, kept for callers that
   * build DTOs without a user join (photos = empty, sellerUsername = null).
   * The JPQL read projections use the 12-arg form below.
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
    this(id, title, description, priceCents, status, sellerId, null, categoryId,
        categoryName, photoUrl, createdAt, updatedAt, List.of());
  }

  /**
   * Projection constructor carrying the seller username: the JPQL read
   * projections in {@link ItemRepository} call this 12-arg form (photos =
   * empty, merged in afterwards by the service where a view needs them).
   */
  public ItemDto(
      Long id,
      String title,
      String description,
      Long priceCents,
      ItemStatus status,
      Long sellerId,
      String sellerUsername,
      Long categoryId,
      String categoryName,
      String photoUrl,
      Instant createdAt,
      Instant updatedAt) {
    this(id, title, description, priceCents, status, sellerId, sellerUsername,
        categoryId, categoryName, photoUrl, createdAt, updatedAt, List.of());
  }
}
