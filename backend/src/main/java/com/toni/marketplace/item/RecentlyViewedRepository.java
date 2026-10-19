package com.toni.marketplace.item;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecentlyViewedRepository extends JpaRepository<RecentlyViewed, Long> {

  /** The one trail row for a (user, listing) pair, if the user has viewed it. */
  Optional<RecentlyViewed> findByUserIdAndItemId(Long userId, Long itemId);

  /**
   * The caller's trail as the same constructor DTO projection the
   * public list view uses (one SELECT joining the listing, its
   * optional category and the seller's username — no per-row lazy
   * loads), most-recent view first (the trail row's own
   * {@code viewedAt}, not the listing's timestamps); the id tiebreak
   * keeps the order deterministic for views landing in the same
   * instant.
   *
   * <p>Only listings still {@code AVAILABLE} are returned — and an
   * AVAILABLE listing past its lifetime deadline (#177) is already
   * gone on every public read, so it is omitted here too. A viewed
   * listing that has since sold, been withdrawn, expired or been
   * deleted is silently omitted (no tombstones in the response): the
   * trail is a "pick up where you left off" surface, not a history
   * archive. The caller's {@link Pageable} carries the documented
   * cap ({@link RecentlyViewedService#RECENT_LIMIT}); the service —
   * never the client — sets it, because the endpoint takes no paging
   * parameters.
   */
  @Query(
      "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, u.username, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt, i.expiresAt) "
          + "from RecentlyViewed rv join Item i on i.id = rv.itemId "
          + "left join i.category c "
          + "left join User u on u.id = i.sellerId "
          + "where rv.userId = :userId "
          + "and i.status = 'AVAILABLE' "
          + "and not (i.expiresAt is not null and i.expiresAt <= current_timestamp) "
          + "order by rv.viewedAt desc, rv.id desc")
  List<ItemDto> findRecentListView(@Param("userId") Long userId, Pageable pageable);

  /**
   * Ids of trail rows whose last view predates {@code viewedBefore} —
   * the retention cut-off computed by the nightly purge job (backlog
   * #195, joining the #152 purge family). The predicate is
   * {@code viewed_at} only: never user, never listing, so the purge
   * cannot selectively erase one person's trail. Ordered by id so
   * the batch loop can always re-read the first page without
   * skipping rows. Mirrors {@code LoginEventRepository.findPurgeableIds}.
   */
  @Query("select rv.id from RecentlyViewed rv where rv.viewedAt < :viewedBefore order by rv.id")
  List<Long> findPurgeableIds(@Param("viewedBefore") Instant viewedBefore, Pageable page);
}
