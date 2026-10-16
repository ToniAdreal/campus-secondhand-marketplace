package com.toni.marketplace.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Saved listings (backlog #143). Both writes are idempotent: saving an
 * already-saved listing is a no-op, and unsaving a listing the caller
 * never saved is equally a no-op — the {@code (user_id, item_id)} unique
 * pair (Flyway V29) is the storage-level guarantee, and a concurrent
 * double-save that still races the check collapses onto it. An unknown
 * listing id is 404 on both writes: "unsaved" describes a real listing
 * the caller has not saved, not a listing that does not exist.
 */
@Service
public class FavoriteService {

  private final FavoriteRepository favorites;
  private final ItemRepository items;
  private final ItemPhotoRepository itemPhotos;
  private final ItemMapper mapper;

  public FavoriteService(FavoriteRepository favorites, ItemRepository items,
                         ItemPhotoRepository itemPhotos, ItemMapper mapper) {
    this.favorites = favorites;
    this.items = items;
    this.itemPhotos = itemPhotos;
    this.mapper = mapper;
  }

  @Transactional
  public void saveFavorite(Long userId, Long itemId) {
    requireItem(itemId);
    if (favorites.existsByUserIdAndItemId(userId, itemId)) {
      return;
    }
    try {
      favorites.saveAndFlush(new Favorite(userId, itemId));
    } catch (DataIntegrityViolationException race) {
      // A concurrent save of the same pair won the unique constraint —
      // the end state is exactly the idempotent one, so it is not an error.
    }
  }

  @Transactional
  public void unsaveFavorite(Long userId, Long itemId) {
    requireItem(itemId);
    favorites.deleteByUserIdAndItemId(userId, itemId);
  }

  /**
   * The caller's saved listings, newest save first, as list-view DTOs.
   * The projection leaves galleries empty (a JPQL constructor projection
   * cannot build the nested collection), so they are merged with one
   * batch query for the whole list — the same bounded pattern as
   * {@link ItemService#listItems}, never per-item.
   */
  @Transactional(readOnly = true)
  public List<ItemDto> listFavorites(Long userId) {
    return withPhotos(favorites.findFavoriteListView(userId));
  }

  private void requireItem(Long itemId) {
    if (!items.existsById(itemId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
    }
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
            byItem.getOrDefault(d.id(), List.of()), d.expiresAt()))
        .toList();
  }
}
