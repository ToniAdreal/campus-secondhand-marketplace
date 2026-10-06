package com.toni.marketplace.item;

import org.mapstruct.Mapper;

/**
 * Compile-time entity &lt;-&gt; DTO mapping. Using MapStruct instead of
 * hand-written mapping keeps the JSON contract in one place and prevents
 * drift as the {@link Item} entity grows.
 */
@Mapper(componentModel = "spring")
public interface ItemMapper {

  ItemDto toDto(Item item);

  /**
   * Creates an entity from a request payload plus the authenticated seller id.
   * Hand-written (not generated): {@link Item} has no sellerId setter and
   * defaults new rows to {@link ItemStatus#AVAILABLE}.
   */
  default Item toEntity(ItemCreateRequest request, Long sellerId) {
    return new Item(request.title(), request.description(), request.priceCents(), sellerId);
  }
}
