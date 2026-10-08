package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MySQL full-text listing search (backlog #68): {@code MATCH(title,
 * description) AGAINST (? IN NATURAL LANGUAGE MODE)} served by the
 * {@code FULLTEXT(title, description)} index from Flyway
 * {@code db/vendor/mysql/V15__item_fulltext_index.sql}.
 *
 * <p>Also proves the runtime dual-path switch in
 * {@link ItemService#listItems}: on this real MySQL the service must take
 * the full-text path (relevance-ordered), not the H2 LIKE path (which would
 * return plain id order). Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test phase.
 */
@Testcontainers
@SpringBootTest
class ItemFulltextSearchMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired
  private ItemService itemService;

  @Autowired
  private ItemRepository items;

  @Autowired
  private CategoryRepository categories;

  private Item listing(String title, String description, Long categoryId) {
    Item item = new Item(title, description, 1000L, 7L);
    if (categoryId != null) {
      item.setCategory(categories.findById(categoryId)
          .orElseThrow(() -> new IllegalStateException("category missing: " + categoryId)));
    }
    return items.save(item);
  }

  @Test
  @Transactional
  void fulltextSearch_returnsRelevanceOrderedMatches() {
    // "bike" occurs twice in the first listing (title + description) vs once
    // in the second: natural-language scoring ranks it first. The textbook
    // matches nothing and is excluded.
    listing("Vintage road bike", "Lightweight road bike, perfect for campus commuting", null);
    listing("Desk lamp", "LED lamp, handy when fixing a bike at night", null);
    listing("Calculus textbook", "Chapters on integrals and derivatives", null);

    Page<ItemDto> page = itemService.listItems(PageRequest.of(0, 20), null, "bike");

    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).extracting(ItemDto::title)
        .containsExactly("Vintage road bike", "Desk lamp");
  }

  @Test
  @Transactional
  void fulltextSearch_composesWithCategoryFilter() {
    Long electronics = categories.findBySlug("electronics")
        .orElseThrow(() -> new IllegalStateException("Flyway V3 did not seed categories"))
        .getId();
    listing("Vintage road bike", "Lightweight road bike for commuting", electronics);
    listing("Road bike bell", "A loud bike bell", null);

    Page<ItemDto> page = itemService.listItems(PageRequest.of(0, 20), electronics, "bike");

    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent()).extracting(ItemDto::title)
        .containsExactly("Vintage road bike");
  }

  @Test
  @Transactional
  void fulltextSearch_blankKeyword_fallsBackToUnfilteredList() {
    listing("Vintage road bike", "Lightweight road bike for commuting", null);

    Page<ItemDto> page = itemService.listItems(PageRequest.of(0, 20), null, "   ");

    assertThat(page.getTotalElements()).isEqualTo(1);
  }
}
