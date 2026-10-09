package com.toni.marketplace.item;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Compile-time entity &lt;-&gt; DTO mapping. Using MapStruct instead of
 * hand-written mapping keeps the JSON contract in one place and prevents
 * drift as the {@link Item} entity grows.
 */
@Mapper(componentModel = "spring")
public interface ItemMapper {

  @Mapping(target = "categoryId", source = "category.id")
  @Mapping(target = "categoryName", source = "category.name")
  // The entity carries only the raw sellerId FK — there is no user data to
  // map from. Read views that expose sellerUsername (list/detail) build
  // their DTOs through the ItemRepository projections instead; DTOs mapped
  // here (mutation responses) carry a null sellerUsername.
  @Mapping(target = "sellerUsername", ignore = true)
  ItemDto toDto(Item item);

  /**
   * Element mapping for the gallery: MapStruct applies it to each row of
   * {@link Item#getPhotos()} when building {@link ItemDto#photos()}. The
   * entity side is already position-ordered ({@code @OrderBy}), so the DTO
   * list keeps gallery order with the primary photo first.
   */
  ItemPhotoDto toPhotoDto(ItemPhoto photo);

  /**
   * Creates an entity from a request payload plus the authenticated seller id.
   * Hand-written (not generated): {@link Item} has no sellerId setter and
   * defaults new rows to {@link ItemStatus#AVAILABLE}. The category is
   * resolved by the service layer — the mapper stays repository-free.
   */
  default Item toEntity(ItemCreateRequest request, Long sellerId, Category category) {
    Item item = new Item(request.title(), request.description(), request.priceCents(), sellerId);
    item.setCategory(category);
    return item;
  }
}
