package com.toni.marketplace.item;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {
  List<Item> findByStatus(ItemStatus status);

  /**
   * Category-filtered list view. Served by the
   * {@code idx_item_category_status_created} composite index
   * (category_id, status, created_at) from Flyway V3.
   */
  Page<Item> findByCategoryId(Long categoryId, Pageable pageable);
}
