package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.UserBlockRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Browse block exclusion (backlog #187), unit level: the caller-aware
 * browse must carry the caller's blocked-seller set into BOTH search
 * paths — the LIKE projection and the MySQL full-text id query (the
 * #86-style pin: one exclusion, two query dialects) — and the
 * full-text hydration must drop a blocked seller's DTO even if an id
 * somehow slips through the id query.
 */
class ItemServiceBlockExclusionTest {

  private final ItemRepository items = mock(ItemRepository.class);
  private final ItemPhotoRepository itemPhotos = mock(ItemPhotoRepository.class);
  private final DataSource dataSource = mock(DataSource.class);
  private final UserBlockRepository blocks = mock(UserBlockRepository.class);

  private ItemService service() {
    return new ItemService(items, mock(CategoryRepository.class), itemPhotos,
        mock(ItemMapper.class), mock(ImageStorageService.class), dataSource,
        null, null, blocks, null, null);
  }

  private static ItemDto dto(long id, long sellerId) {
    return new ItemDto(id, "bike", "desc", 1000L, ItemStatus.AVAILABLE,
        sellerId, null, null, null, null, null);
  }

  @Test
  void likePathUsesExcludingQueryWithCallersBlockedSet() throws Exception {
    // Probe failure falls back to the LIKE path (ItemService contract).
    when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("pool down"));
    when(blocks.findBlockedIdsByBlockerId(7L)).thenReturn(List.of(99L));
    when(items.findListViewFilteredExcludingSellers(eq(null), eq("bike"), eq(null),
        eq(null), eq(List.of(99L)), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    Page<ItemDto> result = service().listItemsForCaller(
        PageRequest.of(0, 20), null, "bike", null, null, null, 7L);

    assertThat(result.getContent()).isEmpty();
    verify(items).findListViewFilteredExcludingSellers(eq(null), eq("bike"), eq(null),
        eq(null), eq(List.of(99L)), any(Pageable.class));
    verify(items, never()).findListViewFiltered(any(), any(), any(), any(), any());
  }

  @Test
  void fulltextPathUsesExcludingIdQueryAndFiltersHydration() throws Exception {
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("MySQL");
    when(blocks.findBlockedIdsByBlockerId(7L)).thenReturn(List.of(99L));
    when(items.findIdsByFulltextExcludingSellers(eq(null), eq("bike"), eq(null),
        eq(null), eq(List.of(99L)), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(1L, 2L), PageRequest.of(0, 20), 2));
    // Hydration returns both rows (the blocked seller's DTO included,
    // simulating an id that slipped through) — the service must drop it.
    when(items.findListViewsByIds(List.of(1L, 2L)))
        .thenReturn(List.of(dto(1L, 5L), dto(2L, 99L)));
    when(itemPhotos.findByItemIdInOrderByPositionAsc(any())).thenReturn(List.of());

    Page<ItemDto> result = service().listItemsForCaller(
        PageRequest.of(0, 20), null, "bike", null, null, null, 7L);

    assertThat(result.getContent()).extracting(ItemDto::id).containsExactly(1L);
    verify(items).findIdsByFulltextExcludingSellers(eq(null), eq("bike"), eq(null),
        eq(null), eq(List.of(99L)), any(Pageable.class));
    verify(items, never()).findIdsByFulltext(any(), any(), any(), any(), any());
  }

  @Test
  void callerWithNoBlocksKeepsTheOriginalQueries() {
    when(blocks.findBlockedIdsByBlockerId(7L)).thenReturn(List.of());
    when(items.findListViewFiltered(eq(null), eq(null), eq(null), eq(null),
        any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

    service().listItemsForCaller(PageRequest.of(0, 20), null, null, null, null, null, 7L);

    verify(items).findListViewFiltered(eq(null), eq(null), eq(null), eq(null),
        any(Pageable.class));
    verify(items, never()).findListViewFilteredExcludingSellers(
        any(), any(), any(), any(), any(), any());
  }
}
