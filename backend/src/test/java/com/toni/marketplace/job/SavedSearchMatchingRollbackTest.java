package com.toni.marketplace.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toni.marketplace.item.ItemDto;
import com.toni.marketplace.item.ItemService;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.item.SavedSearch;
import com.toni.marketplace.item.SavedSearchRepository;
import com.toni.marketplace.notification.NotificationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Saved-search matcher failure semantics (backlog #182): the
 * notification is recorded BEFORE the watermark advances, so when the
 * write fails the watermark object is untouched — and in production
 * the run's transaction rolls back, so a failed run advances nothing
 * in the database either and the next run retries the same matches.
 */
class SavedSearchMatchingRollbackTest {

  @Test
  void aFailedNotificationWriteLeavesTheWatermarkUntouched() {
    SavedSearchRepository savedSearches = mock(SavedSearchRepository.class);
    ItemService itemService = mock(ItemService.class);
    NotificationService notifications = mock(NotificationService.class);

    SavedSearch search = new SavedSearch(7L, "vintage", null, null, null, "newest");
    search.advanceLastNotifiedItemId(10L);
    when(savedSearches.findAllByOrderByIdAsc()).thenReturn(List.of(search));

    ItemDto fresh = new ItemDto(11L, "vintage bike", "seeded", 5000L,
        ItemStatus.AVAILABLE, 99L, null, null, "seeded", Instant.now(), Instant.now());
    when(itemService.listItems(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(fresh), PageRequest.of(0, 200), 1));

    doThrow(new IllegalStateException("notification store down"))
        .when(notifications).recordSavedSearchMatch(anyLong(), any(), anyInt());

    SavedSearchMatchingService matcher =
        new SavedSearchMatchingService(savedSearches, itemService, notifications);

    assertThatThrownBy(matcher::matchSavedSearches)
        .isInstanceOf(IllegalStateException.class);
    assertThat(search.getLastNotifiedItemId()).isEqualTo(10L);
  }
}
