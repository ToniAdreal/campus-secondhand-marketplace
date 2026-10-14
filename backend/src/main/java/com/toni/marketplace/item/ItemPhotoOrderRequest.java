package com.toni.marketplace.item;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Payload for reordering a listing's gallery ({@code PUT
 * /api/items/{id}/photos/order}, backlog #162).
 *
 * <p>{@code photoIds} is the <em>complete</em> gallery in its new
 * display order — the first id becomes the primary photo. The service
 * requires an exact permutation of the listing's current gallery:
 * a missing, extra, duplicated or foreign (another listing's) id is
 * answered 400 with the JSON envelope and nothing is reordered, so a
 * stale client can never silently drop a photo from the gallery by
 * saving an old list.
 */
public record ItemPhotoOrderRequest(
    @NotNull(message = "photoIds is required")
    List<@NotNull(message = "photoIds must not contain null") Long> photoIds) {
}
