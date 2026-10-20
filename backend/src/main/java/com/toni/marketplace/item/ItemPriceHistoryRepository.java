package com.toni.marketplace.item;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemPriceHistoryRepository extends JpaRepository<ItemPriceHistory, Long> {

  /**
   * One listing's full price timeline, oldest first (backlog #201) —
   * the read behind {@code GET /api/items/{id}/price-history}. The id
   * tiebreak keeps the order deterministic when two points land in the
   * same instant (a create followed immediately by an edit under a
   * fixed test clock): insertion order is the true order there.
   */
  List<ItemPriceHistory> findByItemIdOrderByChangedAtAscIdAsc(Long itemId);
}
