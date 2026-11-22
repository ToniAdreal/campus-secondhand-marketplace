package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
 *
 * <p>Backlog #386 triage: the two search tests failed with zero matches
 * not because of the mode, the index, or {@code ft_min_word_len} — the
 * matcher *IT ({@code SavedSearchMatchMySqlIT}) passes under the same
 * natural-language mode with its keyword in 3 of 4 rows — but because
 * these tests seeded inside a {@code @Transactional} method: the MySQL
 * 8.0 manual (§17.6.2.4, "InnoDB Full-Text Index Transaction
 * Handling") states that full-text index updates "are processed at
 * transaction commit time, which means that a full-text search can only
 * see committed data", so the uncommitted seeds were invisible to
 * {@code MATCH}. The tests therefore seed without an outer transaction
 * (each save commits at once) and clean up in {@code @AfterEach},
 * mirroring {@code OrderDisputeTest} / {@code SavedSearchMatchMySqlIT}.
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

  @AfterEach
  void cleanUp() {
    // No @Transactional rollback here (see the class javadoc): the seeds
    // commit at once, so they are deleted explicitly. Categories are
    // Flyway-seeded and untouched by these tests.
    items.deleteAll();
  }

  private Item listing(String title, String description, Long categoryId) {
    Item item = new Item(title, description, 1000L, 7L);
    if (categoryId != null) {
      item.setCategory(categories.findById(categoryId)
          .orElseThrow(() -> new IllegalStateException("category missing: " + categoryId)));
    }
    return items.save(item);
  }

  @Test
  void fulltextSearch_returnsRelevanceOrderedMatches() {
    // Two distinct terms, not one repeated term: MySQL 8.0 natural-language
    // scoring does not rank a document higher for repeating one term twice —
    // the "bike"-only query scored both listings equal (observed 2026-11-20,
    // main runs 44678137904 / 44882267360), so the i.id DESC tiebreak put
    // the later-inserted lamp first. With "road bike" the bike listing
    // matches {road, bike} and the lamp only {bike}; per-term relevance
    // contributions are non-negative and monotone in occurrences, so the
    // bike listing must score strictly higher. The textbook matches
    // neither term and is excluded.
    listing("Vintage road bike", "Lightweight road bike, perfect for campus commuting", null);
    listing("Desk lamp", "LED lamp, handy when fixing a bike at night", null);
    listing("Calculus textbook", "Chapters on integrals and derivatives", null);

    Page<ItemDto> page = itemService.listItems(PageRequest.of(0, 20), null, "road bike");

    assertThat(page.getTotalElements()).isEqualTo(2);
    assertThat(page.getContent()).extracting(ItemDto::title)
        .containsExactly("Vintage road bike", "Desk lamp");
  }

  @Test
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
  void fulltextSearch_blankKeyword_fallsBackToUnfilteredList() {
    listing("Vintage road bike", "Lightweight road bike for commuting", null);

    Page<ItemDto> page = itemService.listItems(PageRequest.of(0, 20), null, "   ");

    assertThat(page.getTotalElements()).isEqualTo(1);
  }
}
