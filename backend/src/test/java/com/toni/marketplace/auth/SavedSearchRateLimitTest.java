package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.item.FavoriteRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.SavedSearch;
import com.toni.marketplace.item.SavedSearchRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate-limit behavior of the saved-search writes through
 * {@code AuthRateLimitFilter} (backlog #313): {@code POST
 * /api/users/me/saved-searches} (save a search) and {@code DELETE
 * /api/users/me/saved-searches/{id}} (delete one) share one
 * per-<em>user</em> bucket (production default 20/minute, like the
 * #282 bucket — pinned to 3/minute here so the burst arithmetic
 * stays small), keyed on the JWT principal id like the message /
 * report / social-graph buckets, with the per-IP fallback for a
 * missing/invalid token. The bucket never resets on success — a
 * completed save or delete must not refill the allowance — and it is
 * deliberately separate from the #308 social-graph bucket (saving a
 * listing vs saving a search are different surfaces despite the
 * shared "save" word). The saved-search list GET is a read and stays
 * unthrottled.
 *
 * <p>Buckets are in-memory and survive across tests within this class,
 * so each test uses fresh users (per-user keys), mirroring
 * {@code SocialGraphRateLimitTest}. Saved searches are seeded
 * repository-direct for the DELETE-burst test so the creates do not
 * consume the very bucket the deletes are meant to exhaust; the
 * favorite writes are idempotent 200s, so a burst can repeat one
 * shape without tripping a second-row guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    // The shared test profile disables the limiter; this class is the one
    // place that exercises the saved-search surfaces, so re-enable it.
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.saved-search-attempts-per-minute=3",
    // Pin the neighbouring #308 bucket to the same small size so the
    // independence tests can exhaust it without a long burst.
    "app.auth.rate-limit.social-graph-attempts-per-minute=3",
    // Per-account lockout stays off so no other throttle masks the bucket
    // assertions.
    "app.auth.login-lockout.enabled=false"})
class SavedSearchRateLimitTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ItemRepository items;
  @Autowired private FavoriteRepository favorites;
  @Autowired private SavedSearchRepository savedSearches;

  private User freshUser() {
    String tag = "savsearch-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String bearer(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item seedItem(User seller) {
    return items.save(new Item("Used film camera", "works, light seals new", 45000L,
        seller.getId()));
  }

  private SavedSearch seedSavedSearch(User owner, String keyword) {
    return savedSearches.save(
        new SavedSearch(owner.getId(), keyword, null, null, null, "newest"));
  }

  private void createOnce(String token, String keyword, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"" + keyword + "\"}"))
        .andExpect(status().is(expectedStatus));
  }

  private void deleteOnce(String token, long id, int expectedStatus) throws Exception {
    mockMvc.perform(delete("/api/users/me/saved-searches/{id}", id)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private void favoriteOnce(String token, long itemId, int expectedStatus) throws Exception {
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", itemId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void singleLegitimateCreatePasses() throws Exception {
    User user = freshUser();
    createOnce(bearer(user), "vintage", 200);
    assertThat(savedSearches.countByUserId(user.getId())).isEqualTo(1);
  }

  @Test
  void singleLegitimateDeletePasses() throws Exception {
    User user = freshUser();
    SavedSearch row = seedSavedSearch(user, "vintage");
    deleteOnce(bearer(user), row.getId(), 200);
    assertThat(savedSearches.findByIdAndUserId(row.getId(), user.getId())).isEmpty();
  }

  @Test
  void createBurstIs429WithEnvelopeAndHeaders() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    createOnce(token, "first", 200);
    createOnce(token, "second", 200);
    createOnce(token, "third", 200);
    // The bucket is empty: the fourth save is throttled before the
    // controller ever sees it, so no fourth row exists.
    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"fourth\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"))
        .andExpect(header -> {
          if (header.getResponse().getHeader("Retry-After") == null) {
            throw new AssertionError("missing Retry-After header on the 429");
          }
          if (header.getResponse().getHeader("RateLimit-Limit") == null) {
            throw new AssertionError("missing RateLimit-Limit header on the 429");
          }
          if (header.getResponse().getHeader("RateLimit-Remaining") == null) {
            throw new AssertionError("missing RateLimit-Remaining header on the 429");
          }
        });
    assertThat(savedSearches.countByUserId(user.getId())).isEqualTo(3);
    assertThat(savedSearches.findByUserIdOrderByCreatedAtDescIdDesc(user.getId()))
        .extracting(SavedSearch::getKeyword)
        .doesNotContain("fourth");
  }

  @Test
  void deleteBurstIs429WithRowIntact() throws Exception {
    // Rows are seeded repository-direct so the creates do not consume
    // the bucket the deletes are meant to exhaust.
    User user = freshUser();
    String token = bearer(user);
    SavedSearch first = seedSavedSearch(user, "first");
    SavedSearch second = seedSavedSearch(user, "second");
    SavedSearch third = seedSavedSearch(user, "third");
    SavedSearch fourth = seedSavedSearch(user, "fourth");
    deleteOnce(token, first.getId(), 200);
    deleteOnce(token, second.getId(), 200);
    deleteOnce(token, third.getId(), 200);
    // The bucket is empty: the fourth delete is throttled before the
    // controller ever sees it, so the row survives — a throttled
    // delete deletes nothing.
    mockMvc.perform(delete("/api/users/me/saved-searches/{id}", fourth.getId())
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("too many requests"));
    assertThat(savedSearches.findByIdAndUserId(fourth.getId(), user.getId())).isPresent();
  }

  @Test
  void savedSearchBucketIsSharedAcrossCreateAndDelete() throws Exception {
    // Exhausting the bucket with saves leaves a delete throttled too —
    // one bucket for the whole saved-search write surface, not one per
    // endpoint. The filter short-circuits before the controller, so
    // the delete writes nothing.
    User user = freshUser();
    String token = bearer(user);
    SavedSearch row = seedSavedSearch(user, "kept");
    createOnce(token, "first", 200);
    createOnce(token, "second", 200);
    createOnce(token, "third", 200);
    deleteOnce(token, row.getId(), 429);
    assertThat(savedSearches.findByIdAndUserId(row.getId(), user.getId())).isPresent();
  }

  @Test
  void savedSearchBucketIsPerUser() throws Exception {
    User first = freshUser();
    String firstToken = bearer(first);
    createOnce(firstToken, "first", 200);
    createOnce(firstToken, "second", 200);
    createOnce(firstToken, "third", 200);
    createOnce(firstToken, "fourth", 429);

    User second = freshUser();
    createOnce(bearer(second), "first", 200);
    assertThat(savedSearches.countByUserId(second.getId())).isEqualTo(1);
  }

  @Test
  void savedSearchBucketExhaustionLeavesSocialGraphBucketIntact() throws Exception {
    User seller = freshUser();
    User actor = freshUser();
    String token = bearer(actor);
    Item item = seedItem(seller);
    createOnce(token, "first", 200);
    createOnce(token, "second", 200);
    createOnce(token, "third", 200);
    createOnce(token, "fourth", 429);
    // The #308 graph bucket is untouched: a save passes.
    favoriteOnce(token, item.getId(), 200);
    assertThat(favorites.existsByUserIdAndItemId(actor.getId(), item.getId())).isTrue();
  }

  @Test
  void socialGraphBucketExhaustionLeavesSavedSearchBucketIntact() throws Exception {
    User seller = freshUser();
    User actor = freshUser();
    String token = bearer(actor);
    Item item = seedItem(seller);
    favoriteOnce(token, item.getId(), 200);
    favoriteOnce(token, item.getId(), 200);
    favoriteOnce(token, item.getId(), 200);
    favoriteOnce(token, item.getId(), 429);
    // The saved-search bucket is untouched: a save passes.
    createOnce(token, "vintage", 200);
    assertThat(savedSearches.countByUserId(actor.getId())).isEqualTo(1);
  }

  @Test
  void listReadsStayUnthrottledAfterExhaustion() throws Exception {
    User user = freshUser();
    String token = bearer(user);
    createOnce(token, "first", 200);
    createOnce(token, "second", 200);
    createOnce(token, "third", 200);
    createOnce(token, "fourth", 429);
    // The list GET carries no id segment, never matches the
    // saved-search matchers, and stays an unthrottled read even with
    // an empty bucket.
    mockMvc.perform(get("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3));
  }
}
