package com.toni.marketplace.item;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserBlock;
import com.toni.marketplace.auth.UserBlockRepository;
import com.toni.marketplace.auth.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Similar listings rail (backlog #202): {@code GET /api/items/{id}/similar}
 * returns the list projection for AVAILABLE listings in the source
 * listing's category — excluding the source itself, other categories,
 * non-AVAILABLE rows, expired rows and listings by sellers the caller
 * has blocked (#187) — in the pinned order (absolute price distance,
 * then newest, then id), capped at {@code ItemService#SIMILAR_LIMIT}.
 * Source visibility mirrors the detail read: unknown / draft-not-yours /
 * expired sources answer 404, anonymous callers 401. An uncategorized
 * source answers an empty list. No recommendation engine, no tracking.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SimilarListingsTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private CategoryRepository categoryRepo;

  @Autowired
  private UserBlockRepository blocks;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  private User seller;
  private User blockedSeller;
  private String sellerToken;
  private String callerToken;
  private String adminToken;
  private Long callerId;
  private Category electronics;
  private Category books;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-sim", "seller-sim@example.com",
        passwords.encode("seller-password")));
    User caller = users.save(new User("caller-sim", "caller-sim@example.com",
        passwords.encode("caller-password")));
    blockedSeller = users.save(new User("blocked-sim", "blocked-sim@example.com",
        passwords.encode("blocked-password")));
    User admin = new User("admin-sim", "admin-sim@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    callerToken = jwt.createTokenPair(caller).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
    callerId = caller.getId();
    electronics = categoryRepo.findBySlug("electronics").orElseThrow();
    books = categoryRepo.findBySlug("books-study").orElseThrow();
  }

  /** Persists a listing row directly, with full control of its state. */
  private Item listing(User owner, String title, long priceCents, Category category,
                       ItemStatus status, Instant expiresAt) {
    Item item = new Item(title, "desc", priceCents, owner.getId());
    item.setCategory(category);
    item.setStatus(status);
    item.setExpiresAt(expiresAt);
    return itemRepo.save(item);
  }

  private Item available(User owner, String title, long priceCents, Category category) {
    return listing(owner, title, priceCents, category, ItemStatus.AVAILABLE,
        Instant.now().plusSeconds(30L * 24 * 3600));
  }

  /** Creates a listing through the API and returns its id. */
  private long createViaApi(String token, String title, long priceCents,
                            Long categoryId, String status) throws Exception {
    StringBuilder body = new StringBuilder("{\"title\":\"" + title
        + "\",\"description\":\"d\",\"priceCents\":" + priceCents);
    if (categoryId != null) {
      body.append(",\"categoryId\":").append(categoryId);
    }
    if (status != null) {
      body.append(",\"status\":\"").append(status).append("\"");
    }
    body.append("}");
    String response = mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.toString()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(response).path("data").path("id").asLong();
  }

  /** Reads the similar ids for {@code sourceId} as {@code token}, in order. */
  private List<Long> similarIds(long sourceId, String token) throws Exception {
    String body = mockMvc.perform(get("/api/items/{id}/similar", sourceId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn().getResponse().getContentAsString();
    List<Long> ids = new ArrayList<>();
    for (JsonNode node : objectMapper.readTree(body).path("data")) {
      ids.add(node.path("id").asLong());
    }
    return ids;
  }

  @Test
  void pinnedOrderAndExclusionMatrix() throws Exception {
    Item source = available(seller, "Source laptop", 10000L, electronics);
    Item near = available(seller, "Near laptop", 10500L, electronics);
    Item tieOlder = available(seller, "Tie older", 9000L, electronics);
    Item tieNewer = available(seller, "Tie newer", 11000L, electronics);
    Item far = available(seller, "Far laptop", 20000L, electronics);
    // Everything below must be absent from the rail.
    available(seller, "Cross category", 10000L, books);
    listing(seller, "Reserved twin", 10000L, electronics, ItemStatus.RESERVED, null);
    listing(seller, "Sold twin", 10000L, electronics, ItemStatus.SOLD, null);
    listing(seller, "Draft twin", 10000L, electronics, ItemStatus.DRAFT, null);
    listing(seller, "Expired twin", 10000L, electronics, ItemStatus.AVAILABLE,
        Instant.now().minusSeconds(3600));

    // Distance 500 first; the two distance-1000 rows newest-first; the
    // distance-10000 row last. The source itself never appears.
    org.assertj.core.api.Assertions.assertThat(similarIds(source.getId(), callerToken))
        .containsExactly(near.getId(), tieNewer.getId(), tieOlder.getId(), far.getId());
  }

  @Test
  void railIsCappedAtTheDocumentedSize() throws Exception {
    Item source = available(seller, "Cap source", 10000L, electronics);
    List<Long> expected = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      Item candidate = available(seller, "Cap candidate " + i, 10000L + i * 100L, electronics);
      if (i <= ItemService.SIMILAR_LIMIT) {
        expected.add(candidate.getId());
      }
    }
    org.assertj.core.api.Assertions.assertThat(similarIds(source.getId(), callerToken))
        .containsExactlyElementsOf(expected);
  }

  @Test
  void blockedSellersAreExcludedForTheBlockingCallerOnly() throws Exception {
    Item source = available(seller, "Block source", 10000L, electronics);
    Item blockedListing = available(blockedSeller, "Blocked closest", 10100L, electronics);
    Item visible = available(seller, "Visible next", 10200L, electronics);
    blocks.save(new UserBlock(callerId, blockedSeller.getId()));

    // The blocking caller never sees the blocked seller's listing, even
    // though it is the closest by price; everyone else still does.
    org.assertj.core.api.Assertions.assertThat(similarIds(source.getId(), callerToken))
        .containsExactly(visible.getId());
    org.assertj.core.api.Assertions.assertThat(similarIds(source.getId(), sellerToken))
        .containsExactly(blockedListing.getId(), visible.getId());
  }

  @Test
  void uncategorizedSourceAnswersAnEmptyRail() throws Exception {
    long sourceId = createViaApi(sellerToken, "Uncategorized mug", 10000L, null, null);
    available(seller, "Categorized laptop", 10000L, electronics);

    mockMvc.perform(get("/api/items/{id}/similar", sourceId)
            .header("Authorization", "Bearer " + callerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void draftSourceIs404ForOthersButReadableBySellerAndAdmin() throws Exception {
    long draftId = createViaApi(sellerToken, "Draft laptop", 10000L,
        electronics.getId(), "DRAFT");
    available(seller, "Live laptop", 10100L, electronics);

    mockMvc.perform(get("/api/items/{id}/similar", draftId)
            .header("Authorization", "Bearer " + callerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(get("/api/items/{id}/similar", draftId)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
    mockMvc.perform(get("/api/items/{id}/similar", draftId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  @Test
  void expiredUnknownAndAnonymousSources() throws Exception {
    Item expired = listing(seller, "Expired source", 10000L, electronics,
        ItemStatus.AVAILABLE, Instant.now().minusSeconds(3600));

    mockMvc.perform(get("/api/items/{id}/similar", expired.getId())
            .header("Authorization", "Bearer " + callerToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/items/{id}/similar", 999999L)
            .header("Authorization", "Bearer " + callerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(get("/api/items/{id}/similar", expired.getId()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
