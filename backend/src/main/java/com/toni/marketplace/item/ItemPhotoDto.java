package com.toni.marketplace.item;

/**
 * Public view of one gallery photo on a listing. The list on
 * {@link ItemDto#photos()} is ordered by position; the first entry is the
 * primary photo the client should render.
 */
public record ItemPhotoDto(
    Long id,
    /** Public URL path ("/uploads/<uuid>.<ext>"), served without auth. */
    String url,
    /** Display order within the listing's gallery; lowest is primary. */
    int position) {
}
