package com.toni.marketplace.item;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ItemService {

  private final ItemRepository items;
  private final CategoryRepository categories;
  private final ItemMapper mapper;

  public ItemService(ItemRepository items, CategoryRepository categories, ItemMapper mapper) {
    this.items = items;
    this.categories = categories;
    this.mapper = mapper;
  }

  /**
   * List view; optionally narrowed to one category. The category path goes
   * through {@code idx_item_category_status_created} (Flyway V3) rather than
   * a full table scan.
   */
  @Transactional(readOnly = true)
  public Page<ItemDto> listItems(Pageable pageable, Long categoryId) {
    Page<Item> page = categoryId == null
        ? items.findAll(pageable)
        : items.findByCategoryId(categoryId, pageable);
    return page.map(mapper::toDto);
  }

  @Transactional(readOnly = true)
  public ItemDto getItem(Long id) {
    return items.findById(id)
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
}
