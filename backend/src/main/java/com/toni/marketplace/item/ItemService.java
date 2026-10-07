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
   * List view; optionally narrowed to one category and/or a free-text
   * keyword. The queries are constructor DTO projections
   * ({@link ItemRepository#findListViewFiltered}) so the whole page —
   * category names included — is rendered by a single SELECT instead of
   * N+1 lazy loads.
   */
  @Transactional(readOnly = true)
  public Page<ItemDto> listItems(Pageable pageable, Long categoryId, String keyword) {
    return items.findListViewFiltered(categoryId, normalizeKeyword(keyword), pageable);
  }

  /**
   * Normalizes a raw {@code ?q=} parameter: trims whitespace, treats blank
   * as "no filter", and escapes the LIKE wildcards ({@code %}, {@code _},
   * backslash) so the search string is matched literally.
   */
  static String normalizeKeyword(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
   * Marks a listing SOLD. Only the AVAILABLE → SOLD and RESERVED → SOLD
   * transitions exist: the requested value must be {@link ItemStatus#SOLD}
   * (this endpoint marks listings sold, it is not a general status machine)
   * and a listing that is already SOLD has no onward transition. Both
   * violations are answered 422 with the JSON envelope. The flip is
   * optimistic-locked via {@code Item.@Version}, so a concurrent flip fails
   * fast instead of silently overwriting.
   *
   * <p>Ownership/role checks live on the controller's {@code @PreAuthorize}
   * ({@link ItemSecurity}); the service stays role-agnostic.
   */
  @Transactional
  public ItemDto markSold(Long id, ItemStatus requested) {
    Item item = items.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (requested != ItemStatus.SOLD) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "only the SOLD transition is supported on this endpoint");
    }
    if (item.getStatus() != ItemStatus.AVAILABLE && item.getStatus() != ItemStatus.RESERVED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "item is already SOLD");
    }
    item.setStatus(ItemStatus.SOLD);
    return mapper.toDto(item);
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
