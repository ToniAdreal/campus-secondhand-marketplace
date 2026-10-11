package com.toni.marketplace.item;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

  boolean existsByUserIdAndItemId(Long userId, Long itemId);

  /** Scoped by user on purpose: one user can never delete another's save. */
  void deleteByUserIdAndItemId(Long userId, Long itemId);

  /**
   * The caller's favorites as the same constructor DTO projection the
   * public list view uses (backlog #7 shape): one SELECT joining the
   * listing, its optional category and the seller's username — no per-row
   * lazy loads. Ordered by save time, newest first (the favorite's own
   * {@code createdAt}, not the listing's); the id tiebreak keeps the
   * order deterministic for saves landing in the same instant. Saved
   * listings are returned whatever their status — a RESERVED or SOLD
   * save stays listed and the DTO's status field is its badge.
   */
  @Query(
      "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt) "
          + "from Favorite f join Item i on i.id = f.itemId "
          + "left join i.category c "
          + "left join User u on u.id = i.sellerId "
          + "where f.userId = :userId "
          + "order by f.createdAt desc, f.id desc")
  List<ItemDto> findFavoriteListView(@Param("userId") Long userId);
}
