package com.toni.marketplace.item;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ItemService {

  private final ItemRepository items;
  private final CategoryRepository categories;
  private final ItemMapper mapper;
  private final ImageStorageService imageStorage;

  public ItemService(ItemRepository items, CategoryRepository categories,
                     ItemMapper mapper, ImageStorageService imageStorage) {
    this.items = items;
    this.categories = categories;
    this.mapper = mapper;
    this.imageStorage = imageStorage;
  }

  /**
   * List view; optionally narrowed to one category. The queries are
   * constructor DTO projections ({@link ItemRepository#findListView}) so the
   * whole page — category names included — is rendered by a single SELECT
   * instead of N+1 lazy loads.
   */
  @Transactional(readOnly = true)
  public Page<ItemDto> listItems(Pageable pageable, Long categoryId) {
    return categoryId == null
        ? items.findListView(pageable)
        : items.findListViewByCategoryId(categoryId, pageable);
  }

  @Transactional(readOnly = true)
  public ItemDto getItem(Long id) {
    return items.findDetailById(id)
        .map(mapper::toDto)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
  }

  /**
   * Creates a listing for the authenticated user. The seller id is the JWT
   * principal ({@link Long}), never a request parameter — a client cannot
   * post on someone else's behalf. An unknown category id is rejected 404
   * with the JSON envelope before anything is persisted.
   */
  @Transactional
  public ItemDto createItem(Long sellerId, ItemCreateRequest request) {
    Category category = null;
    if (request.categoryId() != null) {
      category = categories.findById(request.categoryId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "category not found"));
    }
    Item item = items.save(mapper.toEntity(request, sellerId, category));
    return mapper.toDto(item);
  }

  /**
   * Removes a listing permanently. Role checks live on the controller's
   * {@code @PreAuthorize}; the service stays role-agnostic.
   */
  @Transactional
  public void deleteItem(Long id) {
    Item item = items.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    items.delete(item);
  }

  /**
   * Attaches a photo to a listing: validates and stores the upload, then
   * records its public URL path ({@code /uploads/<uuid>.<ext>}) on the item.
   * Ownership/role checks live on the controller's {@code @PreAuthorize}
   * ({@link ItemSecurity}); the service stays role-agnostic.
   *
   * <p>Note: the file is written to disk before the transaction commits, so
   * a rollback after a successful write can leave an orphan file — accepted
   * at this scale (a periodic cleanup keyed on DB rows would be the
   * production follow-up).
   */
  @Transactional
  public ItemDto attachPhoto(Long itemId, MultipartFile file) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    item.setPhotoUrl(imageStorage.store(file));
    return mapper.toDto(item);
  }
}
