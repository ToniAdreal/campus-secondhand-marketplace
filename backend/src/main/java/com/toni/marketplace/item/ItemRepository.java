package com.toni.marketplace.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {
  List<Item> findByStatus(ItemStatus status);

  /**
   * List view as a constructor DTO projection: one SELECT with an explicit
   * LEFT JOIN for the optional category. Rendering the list through
   * {@code findAll} + entity mapping would fire one lazy SELECT per
   * categorized item (N+1) when the mapper reads
   * {@code item.getCategory().getName()}; the projection fetches everything
   * the list view renders in a single query. The explicit LEFT JOIN (not an
   * implicit path navigation) keeps uncategorized rows in the page.
   * {@code countQuery} drives {@link Page#getTotalElements()}.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt) "
          + "from Item i left join i.category c",
      countQuery = "select count(i) from Item i")
  Page<ItemDto> findListView(Pageable pageable);

  /**
   * Category-filtered list view, same projection as {@link #findListView}.
   * Served by the {@code idx_item_category_status_created} composite index
   * (category_id, status, created_at) from Flyway V3.
   */
  @Query(
      value = "select new com.toni.marketplace.item.ItemDto("
          + "i.id, i.title, i.description, i.priceCents, i.status, i.sellerId, "
          + "c.id, c.name, i.photoUrl, i.createdAt, i.updatedAt) "
          + "from Item i left join i.category c where i.category.id = :categoryId",
      countQuery = "select count(i) from Item i where i.category.id = :categoryId")
  Page<ItemDto> findListViewByCategoryId(@Param("categoryId") Long categoryId, Pageable pageable);

  /**
   * Detail view: JPQL fetch join pulls the optional category together with
   * the item, so the {@link ItemDto} mapping needs no extra lazy query.
   */
  @Query("select i from Item i left join fetch i.category where i.id = :id")
  Optional<Item> findDetailById(@Param("id") Long id);
}
