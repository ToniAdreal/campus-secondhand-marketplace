package com.toni.marketplace.item;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recently-viewed listings (backlog #195): the automatic browsing
 * trail behind the detail read, where {@link FavoriteService} is the
 * deliberate save.
 *
 * <p>Recording shares the view counter's caller rule (#186), enforced
 * by the caller in {@link ItemService#getItemView}: only an
 * authenticated non-seller, non-ADMIN detail read records — the
 * seller checking their own listing and an ADMIN moderating it leave
 * no trail, and list/browse reads never record (they never reach this
 * service). A repeat view upserts: the existing row's
 * {@code viewedAt} moves to now, which is also what puts the listing
 * back at the front of the trail.
 */
@Service
public class RecentlyViewedService {

  /**
   * How many trail entries the read returns at most. The trail is a
   * "pick up where you left off" strip, not an archive: twenty covers
   * a browsing session several times over, and the cap keeps the
   * HomePage strip (and its gallery merge) bounded. Older rows are
   * not deleted when the cap cuts the read — they age out through
   * the retention purge instead.
   */
  static final int RECENT_LIMIT = 20;

  private final RecentlyViewedRepository recentlyViewed;
  private final ItemPhotoRepository itemPhotos;
  private final ItemMapper mapper;

  public RecentlyViewedService(RecentlyViewedRepository recentlyViewed,
                               ItemPhotoRepository itemPhotos, ItemMapper mapper) {
    this.recentlyViewed = recentlyViewed;
    this.itemPhotos = itemPhotos;
    this.mapper = mapper;
  }

  /**
   * Records (or refreshes) one detail view, inside the detail read's
   * own transaction (called from {@link ItemService#getItemView} —
   * deliberately NOT {@code REQUIRES_NEW}: a separate transaction
   * could not see the read's uncommitted state and its trail row
   * would outlive a rolled-back read; the #161 webhook recorder
   * needed that isolation for its ledger, a browsing trail does
   * not). A view that ends in a 304 still records, exactly like the
   * #186 counter beside it: both are bumped inside the read, before
   * the controller's conditional-GET check runs. A concurrent first
   * view of the same listing by the same user that loses the
   * unique-pair race collapses onto the winner's row (the end state
   * is identical) — the same {@code saveAndFlush} + catch pattern as
   * {@link FavoriteService#saveFavorite}.
   */
  @Transactional
  public void recordView(Long userId, Long itemId, Instant when) {
    try {
      recentlyViewed.findByUserIdAndItemId(userId, itemId)
          .ifPresentOrElse(
              row -> row.touch(when),
              () -> recentlyViewed.saveAndFlush(new RecentlyViewed(userId, itemId, when)));
    } catch (DataIntegrityViolationException race) {
      // A concurrent first view of the same pair won the unique
      // constraint — the winner's row IS this view's trail entry.
    }
  }

  /**
   * The caller's trail, most-recent first, capped at
   * {@value #RECENT_LIMIT}, as list-view DTOs for listings still
   * AVAILABLE (the repository query omits sold/expired/deleted rows).
   * The projection leaves galleries empty, so they are merged with
   * one batch query for the whole list — the same bounded pattern as
   * {@link FavoriteService#listFavorites}, never per-item.
   */
  @Transactional(readOnly = true)
  public List<ItemDto> listRecentlyViewed(Long userId) {
    return withPhotos(recentlyViewed.findRecentListView(
        userId, PageRequest.of(0, RECENT_LIMIT)));
  }

  private List<ItemDto> withPhotos(List<ItemDto> dtos) {
    if (dtos.isEmpty()) {
      return dtos;
    }
    List<Long> ids = dtos.stream().map(ItemDto::id).toList();
    Map<Long, List<ItemPhotoDto>> byItem = new HashMap<>();
    for (ItemPhoto photo : itemPhotos.findByItemIdInOrderByPositionAsc(ids)) {
      byItem.computeIfAbsent(photo.getItem().getId(), k -> new ArrayList<>())
          .add(mapper.toPhotoDto(photo));
    }
    return dtos.stream()
        .map(d -> new ItemDto(
            d.id(), d.title(), d.description(), d.priceCents(), d.status(),
            d.sellerId(), d.sellerUsername(), d.categoryId(), d.categoryName(),
            d.photoUrl(), d.createdAt(), d.updatedAt(),
            byItem.getOrDefault(d.id(), List.of()), d.expiresAt(), d.viewCount()))
        .toList();
  }
}
