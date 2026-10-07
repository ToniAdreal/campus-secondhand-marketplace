package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit tests for {@link ItemService}: no Spring context, no database.
 * Repositories, the mapper, and the storage service are Mockito mocks, so
 * these tests exercise exactly the service's orchestration logic — category
 * lookups, not-found guards, keyword normalization, and photo wiring.
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

  @Mock
  private ItemRepository items;

  @Mock
  private CategoryRepository categories;

  @Mock
  private ItemMapper mapper;

  @Mock
  private ImageStorageService imageStorage;

  @InjectMocks
  private ItemService service;

  private static ItemCreateRequest request(String title, Long categoryId) {
    return new ItemCreateRequest(title, "a used desk lamp", 2500L, categoryId);
  }

  private static Item listing(long sellerId) {
    return new Item("Desk lamp", "a used desk lamp", 2500L, sellerId);
  }

  // --- listItems ---

  @Test
  void listItems_normalizesKeywordBeforeQuerying() {
    Pageable pageable = PageRequest.of(0, 20);
    Page<ItemDto> page = new PageImpl<>(java.util.List.of());
    when(items.findListViewFiltered(eq(7L), eq("100\\%"), eq(pageable))).thenReturn(page);

    Page<ItemDto> result = service.listItems(pageable, 7L, "  100% ");

    assertThat(result).isSameAs(page);
    verify(items).findListViewFiltered(7L, "100\\%", pageable);
  }

  @Test
  void listItems_blankKeyword_passesNull() {
    Pageable pageable = PageRequest.of(0, 20);
    when(items.findListViewFiltered(null, null, pageable))
        .thenReturn(new PageImpl<>(java.util.List.of()));

    service.listItems(pageable, null, "   ");

    verify(items).findListViewFiltered(null, null, pageable);
  }

  // --- getItem ---

  @Test
  void getItem_returnsDtoWhenFound() {
    Item item = listing(5L);
    ItemDto dto = new ItemDto(3L, "Desk lamp", "a used desk lamp", 2500L, ItemStatus.AVAILABLE,
        5L, null, null, null, null, null);
    when(items.findDetailById(3L)).thenReturn(Optional.of(item));
    when(mapper.toDto(item)).thenReturn(dto);

    assertThat(service.getItem(3L)).isSameAs(dto);
  }

  @Test
  void getItem_missing_throws404() {
    when(items.findDetailById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getItem(9L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
  }

  // --- createItem ---

  @Test
  void createItem_withoutCategory_skipsCategoryLookup() {
    ItemCreateRequest req = request("Desk lamp", null);
    Item entity = listing(5L);
    ItemDto dto = new ItemDto(1L, "Desk lamp", "a used desk lamp", 2500L, ItemStatus.AVAILABLE,
        5L, null, null, null, null, null);
    when(mapper.toEntity(req, 5L, null)).thenReturn(entity);
    when(items.save(entity)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    assertThat(service.createItem(5L, req)).isSameAs(dto);
    verify(categories, never()).findById(any());
    verify(items).save(entity);
  }

  @Test
  void createItem_withCategory_resolvesItFirst() {
    ItemCreateRequest req = request("Desk lamp", 2L);
    Category category = new Category("Electronics", "electronics");
    Item entity = listing(5L);
    ItemDto dto = new ItemDto(1L, "Desk lamp", "a used desk lamp", 2500L, ItemStatus.AVAILABLE,
        5L, 2L, "Electronics", null, null, null);
    when(categories.findById(2L)).thenReturn(Optional.of(category));
    when(mapper.toEntity(req, 5L, category)).thenReturn(entity);
    when(items.save(entity)).thenReturn(entity);
    when(mapper.toDto(entity)).thenReturn(dto);

    assertThat(service.createItem(5L, req)).isSameAs(dto);
    verify(items).save(entity);
  }

  @Test
  void createItem_unknownCategory_throws404AndSavesNothing() {
    ItemCreateRequest req = request("Desk lamp", 99L);
    when(categories.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createItem(5L, req))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    verify(items, never()).save(any());
  }

  // --- deleteItem ---

  @Test
  void deleteItem_deletesExistingListing() {
    Item item = listing(5L);
    when(items.findById(3L)).thenReturn(Optional.of(item));

    service.deleteItem(3L);

    verify(items).delete(item);
  }

  @Test
  void deleteItem_missing_throws404AndNeverDeletes() {
    when(items.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteItem(9L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    verify(items, never()).delete(any());
  }

  // --- markSold ---

  @Test
  void markSold_flipsAvailableToSoldAndReturnsDto() {
    Item item = listing(5L);
    ItemDto dto = new ItemDto(3L, "Desk lamp", "a used desk lamp", 2500L, ItemStatus.SOLD,
        5L, null, null, null, null, null);
    when(items.findById(3L)).thenReturn(Optional.of(item));
    when(mapper.toDto(item)).thenReturn(dto);

    assertThat(service.markSold(3L, ItemStatus.SOLD)).isSameAs(dto);
    assertThat(item.getStatus()).isEqualTo(ItemStatus.SOLD);
  }

  @Test
  void markSold_reservedListingCanBeMarkedSold() {
    Item item = listing(5L);
    item.setStatus(ItemStatus.RESERVED);
    ItemDto dto = new ItemDto(3L, "Desk lamp", "a used desk lamp", 2500L, ItemStatus.SOLD,
        5L, null, null, null, null, null);
    when(items.findById(3L)).thenReturn(Optional.of(item));
    when(mapper.toDto(item)).thenReturn(dto);

    service.markSold(3L, ItemStatus.SOLD);

    assertThat(item.getStatus()).isEqualTo(ItemStatus.SOLD);
  }

  @Test
  void markSold_nonSoldRequested_throws422AndKeepsStatus() {
    Item item = listing(5L);
    when(items.findById(3L)).thenReturn(Optional.of(item));

    assertThatThrownBy(() -> service.markSold(3L, ItemStatus.AVAILABLE))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(item.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
    verify(mapper, never()).toDto(any());
  }

  @Test
  void markSold_alreadySold_throws422() {
    Item item = listing(5L);
    item.setStatus(ItemStatus.SOLD);
    when(items.findById(3L)).thenReturn(Optional.of(item));

    assertThatThrownBy(() -> service.markSold(3L, ItemStatus.SOLD))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void markSold_missing_throws404() {
    when(items.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.markSold(9L, ItemStatus.SOLD))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    verify(mapper, never()).toDto(any());
  }

  // --- attachPhoto ---

  @Test
  void attachPhoto_storesFileAndRecordsUrl() {
    Item item = listing(5L);
    var file = new MockMultipartFile("file", "lamp.png", "image/png", new byte[]{1, 2, 3});
    ItemDto dto = new ItemDto(3L, "Desk lamp", "a used desk lamp", 2500L, ItemStatus.AVAILABLE,
        5L, null, null, "/uploads/abc.png", null, null);
    when(items.findById(3L)).thenReturn(Optional.of(item));
    when(imageStorage.store(file)).thenReturn("/uploads/abc.png");
    when(mapper.toDto(item)).thenReturn(dto);

    assertThat(service.attachPhoto(3L, file)).isSameAs(dto);
    assertThat(item.getPhotoUrl()).isEqualTo("/uploads/abc.png");
    verify(imageStorage).store(file);
  }

  @Test
  void attachPhoto_missingItem_throws404AndNeverStores() {
    var file = new MockMultipartFile("file", "lamp.png", "image/png", new byte[]{1, 2, 3});
    when(items.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.attachPhoto(9L, file))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    verify(imageStorage, never()).store(any());
  }
}
