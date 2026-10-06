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
  private final ItemMapper mapper;

  public ItemService(ItemRepository items, ItemMapper mapper) {
    this.items = items;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public Page<ItemDto> listItems(Pageable pageable) {
    return items.findAll(pageable).map(mapper::toDto);
  }

  @Transactional(readOnly = true)
  public ItemDto getItem(Long id) {
    return items.findById(id)
        .map(mapper::toDto)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
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
