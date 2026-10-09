package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.auth.User;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * N+1 regression tests for the item reads. The list view is a constructor
 * DTO projection ({@link ItemRepository#findListView}) and the detail view
 * uses a JPQL fetch join ({@link ItemRepository#findDetailById}); both must
 * render a page including category names with exactly one SELECT and no
 * lazy association round-trips. Hibernate statistics prove it: before the
 * fix, the same assertions failed with 1 + N statements (one lazy SELECT
 * per categorized item fired when the mapper read
 * {@code item.getCategory().getName()}).
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ItemListViewQueryTest {

  @Autowired
  private ItemRepository items;

  @Autowired
  private CategoryRepository categories;

  @Autowired
  private TestEntityManager em;

  private Statistics stats;
  private Long laptopId;
  private Long electronicsId;
  private Long sellerId;

  @BeforeEach
  void seed() {
    stats = em.getEntityManager().getEntityManagerFactory()
        .unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    // A real seller row: the projections resolve sellerUsername through an
    // ad-hoc join on the raw sellerId FK.
    User seller = em.persist(new User("seller-seven", "seller-seven@example.com", "hash"));
    em.flush();
    sellerId = seller.getId();

    Category electronics = categories.findBySlug("electronics")
        .orElseThrow(() -> new IllegalStateException("Flyway V3 did not seed categories"));
    Item laptop = new Item("Used ThinkPad", "T480, good battery", 129900L, sellerId);
    laptop.setCategory(electronics);
    Item cable = new Item("USB-C cable", "spare", 500L, sellerId);
    cable.setCategory(electronics);
    Item mug = new Item("Free mug", "pickup only", 100L, sellerId); // uncategorized
    items.save(laptop);
    items.save(cable);
    items.save(mug);
    em.flush();
    em.clear();

    laptopId = laptop.getId();
    electronicsId = electronics.getId();
  }

  @Test
  void listViewRendersWholePageInASingleQuery() {
    stats.clear();

    Page<ItemDto> page = items.findListView(PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(3);
    // category names arrive inside the DTOs — reading them must not query
    assertThat(page.getContent())
        .filteredOn(dto -> dto.categoryName() != null)
        .hasSize(2)
        .allSatisfy(dto -> {
          assertThat(dto.categoryName()).isEqualTo("Electronics");
          assertThat(dto.categoryId()).isEqualTo(electronicsId);
        });
    assertThat(page.getContent())
        .filteredOn(dto -> dto.categoryName() == null)
        .hasSize(1)
        .allSatisfy(dto -> assertThat(dto.categoryId()).isNull());
    // the seller username arrives inside the same projection — reading it
    // for every row must not query either
    assertThat(page.getContent())
        .allSatisfy(dto -> {
          assertThat(dto.sellerId()).isEqualTo(sellerId);
          assertThat(dto.sellerUsername()).isEqualTo("seller-seven");
        });
    // exactly one SELECT: no count query (3 rows < page size 20) and,
    // crucially, no lazy per-item category SELECTs
    assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
  }

  @Test
  void detailViewProjectionCarriesSellerUsernameInASingleQuery() {
    stats.clear();

    ItemDto dto = items.findDetailViewById(laptopId).orElseThrow();

    assertThat(dto.title()).isEqualTo("Used ThinkPad");
    assertThat(dto.categoryName()).isEqualTo("Electronics");
    assertThat(dto.sellerId()).isEqualTo(sellerId);
    assertThat(dto.sellerUsername()).isEqualTo("seller-seven");
    assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
  }

  @Test
  void categoryFilteredListViewRendersInASingleQuery() {
    stats.clear();

    Page<ItemDto> page = items.findListViewByCategoryId(electronicsId, PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(2);
    assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
  }

  @Test
  void listViewCountQueryDrivesPagination() {
    // page size below the row count forces the countQuery to run — this
    // proves it parses and returns the right totals
    Page<ItemDto> page = items.findListView(PageRequest.of(0, 2));

    assertThat(page.getContent()).hasSize(2);
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getTotalPages()).isEqualTo(2);
  }

  @Test
  void detailViewFetchJoinsCategoryInASingleQuery() {
    stats.clear();

    Item item = items.findDetailById(laptopId).orElseThrow();

    assertThat(item.getCategory().getName()).isEqualTo("Electronics");
    assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
  }
}
