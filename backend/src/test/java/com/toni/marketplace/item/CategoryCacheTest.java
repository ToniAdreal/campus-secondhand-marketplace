package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.common.DuplicateCategoryException;
import jakarta.persistence.EntityManagerFactory;
import java.util.EnumSet;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category-list caching (backlog #87): {@code GET /api/categories} is read
 * on every browse render, so {@link CategoryService#listAll()} is cached in
 * the JVM-local {@code categories} region and the ADMIN write paths
 * ({@code POST}/{@code DELETE /api/categories}, backlog #70) evict it.
 *
 * <p>Proof strategy mirrors the N+1 tests (#7): Hibernate statistics count
 * the prepared statements behind the service calls — a second
 * {@code listAll()} must issue none. The cache is process-wide state shared
 * with the other test classes in this context, so every test here clears
 * it before and after itself; the direct-repository saves below bypass the
 * service (and its eviction) on purpose to prove a read really came from
 * the cache.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
class CategoryCacheTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private CategoryService categoryService;

  @Autowired
  private CategoryRepository categoryRepo;

  @Autowired
  private UserRepository users;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private CacheManager cacheManager;

  @Autowired
  private EntityManagerFactory entityManagerFactory;

  private Statistics stats;
  private String adminToken;

  @BeforeEach
  void setUp() {
    clearCache();
    stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    User admin = new User("cache-admin", "cache-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  @AfterEach
  void tearDown() {
    // The surrounding test transaction rolls the rows back, but cache
    // entries would survive into other test classes — never leak them.
    clearCache();
  }

  private void clearCache() {
    var cache = cacheManager.getCache(CategoryCacheConfig.CATEGORIES_CACHE);
    assertThat(cache).isNotNull();
    cache.clear();
  }

  @Test
  void secondListAllServesFromCacheWithoutAnotherQuery() {
    var first = categoryService.listAll();
    assertThat(first).hasSize(6); // Flyway V3 seed

    stats.clear();
    var second = categoryService.listAll();

    assertThat(second).isEqualTo(first);
    assertThat(stats.getPrepareStatementCount())
        .as("a cached listAll() must not hit the database again")
        .isZero();
  }

  @Test
  void cachedListIgnoresDirectRepositoryWritesUntilEvicted() {
    categoryService.listAll(); // warm the cache

    // Bypass the service (no eviction): the row exists in the DB but a
    // cached read must not see it — that is what makes it a cache.
    categoryRepo.save(new Category("Sneaky Direct", "sneaky-direct"));

    assertThat(categoryService.listAll())
        .extracting(CategoryDto::slug)
        .doesNotContain("sneaky-direct");
  }

  @Test
  void adminCreateViaHttpEvictsTheCachedList() throws Exception {
    categoryService.listAll(); // warm the cache

    mockMvc.perform(post("/api/categories")
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Cache Busters\"}"))
        .andExpect(status().isCreated());

    assertThat(categoryService.listAll())
        .extracting(CategoryDto::slug)
        .contains("cache-busters");
  }

  @Test
  void adminDeleteViaHttpEvictsTheCachedList() throws Exception {
    Category temp = categoryRepo.save(new Category("Doomed Category", "doomed-category"));
    assertThat(categoryService.listAll())
        .extracting(CategoryDto::slug)
        .contains("doomed-category"); // now cached, temp included

    mockMvc.perform(delete("/api/categories/{id}", temp.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNoContent());

    assertThat(categoryService.listAll())
        .extracting(CategoryDto::slug)
        .doesNotContain("doomed-category");
  }

  @Test
  void failedCreateDoesNotEvictTheCachedList() {
    var cached = categoryService.listAll(); // warm the cache

    // "Electronics" is seeded by Flyway V3 — the create is rejected, and a
    // rejected write must not flush a warm cache as a side effect.
    assertThatThrownBy(() -> categoryService.createCategory("electronics", null))
        .isInstanceOf(DuplicateCategoryException.class);

    categoryRepo.save(new Category("Marker Row", "marker-row")); // bypass, no eviction
    assertThat(categoryService.listAll())
        .as("the pre-failure cached list must still be served untouched")
        .isEqualTo(cached);
  }
}
