package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved searches (backlog #164): POST/GET/DELETE
 * /api/users/me/saved-searches are scoped to the caller (someone else's
 * id is 404 — no existence leak), creation reuses the browse filter
 * validation (400s), the per-user cap of 20 answers 422, and each row's
 * matchCount equals what the browse endpoint itself returns for the
 * same filters against the seeded listings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SavedSearchTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private CategoryRepository categoryRepo;
  @Autowired private SavedSearchRepository savedSearches;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private ObjectMapper objectMapper;

  private User seller;
  private String buyerToken;
  private String otherToken;
  private Long buyerId;
  private Long otherId;
  private Category category;

  @BeforeEach
  void createUsersTokensAndCategory() {
    String tag = "ss-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    User buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    User other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    buyerId = buyer.getId();
    otherId = other.getId();
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    category = categoryRepo.save(new Category("SS Cat " + tag, "ss-cat-" + tag));
  }

  private Item seedItem(String title, long priceCents, boolean categorized, ItemStatus status) {
    Item item = new Item(title, "seeded", priceCents, seller.getId());
    if (categorized) {
      item.setCategory(category);
    }
    item.setStatus(status);
    return itemRepo.save(item);
  }

  /** The seeded browse universe the matchCount assertions run against. */
  private void seedListings() {
    seedItem("Vintage bike frame", 5000L, true, ItemStatus.AVAILABLE);
    seedItem("Bike helmet", 15000L, true, ItemStatus.AVAILABLE);
    seedItem("Vintage poster", 5000L, false, ItemStatus.AVAILABLE);
    // SOLD rows ride along in the browse projection (it has no status
    // predicate), so a saved search counts them too — pinned here.
    seedItem("Vintage bike bell", 5000L, true, ItemStatus.SOLD);
  }

  private long browseTotal(String token, String query) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/items?" + query)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
    return root.path("data").path("totalElements").asLong();
  }

  @Test
  void createThenListReturnsTheRowWithItsFiltersAndMatchCount() throws Exception {
    seedListings();

    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"vintage\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.keyword").value("vintage"))
        .andExpect(jsonPath("$.data.sort").value("newest"))
        .andExpect(jsonPath("$.data.matchCount").value(3));

    // The count is exactly what the browse endpoint returns for ?q=vintage.
    assertThat(browseTotal(buyerToken, "q=vintage")).isEqualTo(3);

    mockMvc.perform(get("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].keyword").value("vintage"))
        .andExpect(jsonPath("$.data[0].matchCount").value(3));
  }

  @Test
  void matchCountRespectsCategoryAndPriceFiltersLikeBrowse() throws Exception {
    seedListings();

    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"vintage\",\"categoryId\":" + category.getId() + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.matchCount").value(2));
    assertThat(browseTotal(buyerToken, "q=vintage&categoryId=" + category.getId()))
        .isEqualTo(2);

    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"minPriceCents\":4000,\"maxPriceCents\":6000,\"sort\":\"price-asc\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sort").value("price-asc"))
        .andExpect(jsonPath("$.data.matchCount").value(3));
    assertThat(browseTotal(buyerToken,
        "minPriceCents=4000&maxPriceCents=6000&sort=price-asc")).isEqualTo(3);
  }

  @Test
  void listIsNewestFirst() throws Exception {
    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"first\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"second\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].keyword").value("second"))
        .andExpect(jsonPath("$.data[1].keyword").value("first"));
  }

  @Test
  void invalidFilterSetsAre400() throws Exception {
    // min above max — the browse service's own rule.
    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"minPriceCents\":9000,\"maxPriceCents\":100}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    // Negative bound.
    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"minPriceCents\":-5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    // Sort outside the allowlist.
    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sort\":\"cheapest-ish\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(savedSearches.countByUserId(buyerId)).isZero();
  }

  @Test
  void twentyFirstSavedSearchIs422() throws Exception {
    for (int i = 0; i < SavedSearchService.MAX_SAVED_SEARCHES; i++) {
      savedSearches.save(new SavedSearch(buyerId, "seed-" + i, null, null, null, "newest"));
    }

    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"one-too-many\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(savedSearches.countByUserId(buyerId))
        .isEqualTo(SavedSearchService.MAX_SAVED_SEARCHES);
  }

  @Test
  void deleteOwnSavedSearchRemovesIt() throws Exception {
    SavedSearch saved = savedSearches.save(
        new SavedSearch(buyerId, "mine", null, null, null, "newest"));

    mockMvc.perform(delete("/api/users/me/saved-searches/{id}", saved.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    mockMvc.perform(get("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void otherUsersSavedSearchIsInvisibleAndItsDeleteIs404() throws Exception {
    SavedSearch saved = savedSearches.save(
        new SavedSearch(buyerId, "mine", null, null, null, "newest"));

    // The other user's list never leaks the buyer's saved search…
    mockMvc.perform(get("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));

    // …and deleting it by id answers 404, exactly like an unknown id,
    // so the row's existence is never leaked. The row survives.
    mockMvc.perform(delete("/api/users/me/saved-searches/{id}", saved.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(delete("/api/users/me/saved-searches/{id}", 999_999L)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    assertThat(savedSearches.countByUserId(buyerId)).isEqualTo(1);
    assertThat(savedSearches.countByUserId(otherId)).isZero();
  }

  @Test
  void anonymousCallersGet401() throws Exception {
    mockMvc.perform(post("/api/users/me/saved-searches")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"vintage\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/users/me/saved-searches"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/users/me/saved-searches/{id}", 1L))
        .andExpect(status().isUnauthorized());
  }
}
