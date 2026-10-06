package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * Unit tests for the generated {@link ItemMapper} implementation: every field
 * of the entity view is carried over, and the DTO stays free of JPA
 * internals (no lazy associations, no optimistic-lock {@code version}).
 */
class ItemMapperTest {

  private final ItemMapper mapper = Mappers.getMapper(ItemMapper.class);

  @Test
  void toDtoCarriesEveryPublicField() {
    Item item = new Item("Used ThinkPad", "T480, good battery", 129900L, 7L);
    item.setStatus(ItemStatus.RESERVED);

    ItemDto dto = mapper.toDto(item);

    assertThat(dto.title()).isEqualTo("Used ThinkPad");
    assertThat(dto.description()).isEqualTo("T480, good battery");
    assertThat(dto.priceCents()).isEqualTo(129900L);
    assertThat(dto.status()).isEqualTo(ItemStatus.RESERVED);
    assertThat(dto.sellerId()).isEqualTo(7L);
  }

  @Test
  void dtoExposesNoPersistenceInternals() {
    // The optimistic-lock version and any JPA lazy proxies must never reach
    // the JSON contract; assert the DTO simply has no such members.
    assertThat(Arrays.stream(ItemDto.class.getDeclaredFields()).map(f -> f.getName()))
        .doesNotContain("version");

    assertThat(Stream.of(ItemDto.class.getDeclaredFields())
        .noneMatch(f -> {
          String pkg = f.getType().getPackageName();
          return pkg.startsWith("jakarta.persistence") || pkg.startsWith("org.hibernate");
        }))
        .as("DTO must not reference JPA or Hibernate types — entities never leak into JSON")
        .isTrue();
  }

  @Test
  void toEntityBindsRequestWithSellerId() {
    ItemCreateRequest request = new ItemCreateRequest("Bike", "City bike", 45000L);

    Item item = mapper.toEntity(request, 42L);

    assertThat(item.getTitle()).isEqualTo("Bike");
    assertThat(item.getDescription()).isEqualTo("City bike");
    assertThat(item.getPriceCents()).isEqualTo(45000L);
    assertThat(item.getSellerId()).isEqualTo(42L);
    assertThat(item.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
  }

  @Test
  void dtoRoundTripPreservesValues() {
    ItemCreateRequest request = new ItemCreateRequest("Lamp", "Desk lamp", 8000L);

    ItemDto dto = mapper.toDto(mapper.toEntity(request, 9L));

    assertThat(dto.title()).isEqualTo("Lamp");
    assertThat(dto.priceCents()).isEqualTo(8000L);
    assertThat(dto.sellerId()).isEqualTo(9L);
    assertThat(dto.status()).isEqualTo(ItemStatus.AVAILABLE);
  }
}
