package com.toni.marketplace.item;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemPhotoRepository extends JpaRepository<ItemPhoto, Long> {

  /** Gallery of one listing, display order (lowest position first). */
  List<ItemPhoto> findByItemIdOrderByPositionAsc(Long itemId);

  /**
   * Galleries for a page of listings in one query — the service merges them
   * into the list-view DTOs, so a page never degrades into N+1 lazy loads.
   */
  List<ItemPhoto> findByItemIdInOrderByPositionAsc(Collection<Long> itemIds);

  long countByItemId(Long itemId);

  /** Highest position on the listing, for appending at the end. */
  Optional<ItemPhoto> findTopByItemIdOrderByPositionDesc(Long itemId);

  /** Scoped lookup: a photo id is only meaningful under its own listing. */
  Optional<ItemPhoto> findByIdAndItemId(Long id, Long itemId);
}
