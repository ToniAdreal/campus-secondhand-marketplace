package com.toni.marketplace.item;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Saved searches (backlog #164): a caller-owned store of browse filter
 * sets, in the #143 favourites mould. Two deliberate reuse decisions keep
 * a saved search from ever drifting away from the browse page it restores:
 *
 * <ul>
 *   <li><b>Validation</b> — creation runs the filter set through
 *       {@link ItemService#validatePriceRangeAndSort} (negative bound,
 *       min &gt; max, or a sort outside {newest, price-asc, price-desc}
 *       is the same 400 the browse endpoint answers), and the canonical
 *       sort name it returns is what gets stored. A saved value is
 *       therefore always a browsable value.</li>
 *   <li><b>matchCount</b> — each row's count is computed on read by
 *       calling {@link ItemService#listItems} itself with a one-row page
 *       and reading its total: the SAME filtered projection/browse query
 *       the browse page uses, including {@code ItemService}'s own choice
 *       between the LIKE projection (H2) and the MySQL full-text native
 *       path. There is no second count query and no second query dialect
 *       to drift; the cost is one bounded browse query per saved row,
 *       capped by {@link #MAX_SAVED_SEARCHES} rows per user.</li>
 * </ul>
 *
 * <p>Scope, honestly: v1 has no email alerts and no background matching
 * job — the count exists only in the moment the list is read.
 */
@Service
public class SavedSearchService {

  /** Per-user cap (backlog #164): beyond it creation is 422, never a silent truncation. */
  static final int MAX_SAVED_SEARCHES = 20;

  private final SavedSearchRepository savedSearches;
  private final ItemService itemService;

  public SavedSearchService(SavedSearchRepository savedSearches, ItemService itemService) {
    this.savedSearches = savedSearches;
    this.itemService = itemService;
  }

  @Transactional
  public SavedSearchDto createSavedSearch(Long userId, SavedSearchCreateRequest request) {
    String canonicalSort = ItemService.validatePriceRangeAndSort(
        request.minPriceCents(), request.maxPriceCents(), request.sort());
    if (savedSearches.countByUserId(userId) >= MAX_SAVED_SEARCHES) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "saved search limit reached: a user holds at most "
              + MAX_SAVED_SEARCHES + " saved searches");
    }
    SavedSearch saved = savedSearches.save(new SavedSearch(
        userId, normalizeKeyword(request.keyword()), request.categoryId(),
        request.minPriceCents(), request.maxPriceCents(), canonicalSort));
    return toDto(saved);
  }

  /** The caller's saved searches, newest first, each with its live match count. */
  @Transactional(readOnly = true)
  public List<SavedSearchDto> listSavedSearches(Long userId) {
    return savedSearches.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
        .map(this::toDto)
        .toList();
  }

  /**
   * Deletes one of the caller's saved searches. The lookup is scoped to
   * the caller: an id that belongs to someone else (or to nobody) is 404
   * — the row's existence is never leaked.
   */
  @Transactional
  public void deleteSavedSearch(Long userId, Long id) {
    SavedSearch saved = savedSearches.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "saved search not found"));
    savedSearches.delete(saved);
  }

  private SavedSearchDto toDto(SavedSearch saved) {
    long matchCount = itemService.listItems(
            PageRequest.of(0, 1), saved.getCategoryId(), saved.getKeyword(),
            saved.getMinPriceCents(), saved.getMaxPriceCents(), saved.getSort())
        .getTotalElements();
    return new SavedSearchDto(
        saved.getId(), saved.getKeyword(), saved.getCategoryId(),
        saved.getMinPriceCents(), saved.getMaxPriceCents(), saved.getSort(),
        matchCount, saved.getCreatedAt());
  }

  /** Trims the keyword; blank means "no keyword filter", exactly as on the browse page. */
  private static String normalizeKeyword(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return raw.trim();
  }
}
