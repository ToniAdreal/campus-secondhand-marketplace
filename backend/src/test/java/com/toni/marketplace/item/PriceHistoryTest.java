package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listing price history (backlog #201): creating a listing seeds the
 * first point at its creation price; a PATCH edit that actually
 * changes {@code priceCents} appends exactly one point, while a no-op
 * price edit, a title-only edit, a status flip and a photo change
 * append nothing. {@code GET /api/items/{id}/price-history} returns
 * the points oldest-first, each carrying exactly {@code priceCents} +
 * {@code changedAt} (key-set pin — no editor identity). Any
 * authenticated user may read a live listing's history; a DRAFT
 * answers 404 to everyone but its seller and ADMINs, a WITHDRAWN or
 * expired listing answers 404 to everyone, an unknown id is 404 and
 * anonymous callers get 401 from the security chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PriceHistoryTest {

  /** Minimal valid PNG (1x1 pixel) — same fixture as ItemPhotoUploadTest. */
  private static final byte[] PNG_BYTES = {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
      (byte) 0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41,
      0x54, 0x78, (byte) 0x9C, 0x63, 0x60, 0x00, 0x02, 0x00,
      0x00, 0x05, 0x00, 0x01, 0x7A, 0x5E, (byte) 0xAB, 0x3F,
      0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
      (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private ItemPriceHistoryRepository historyRepo;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  private User seller;
  private String sellerToken;
  private String otherToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-ph", "seller-ph@example.com",
        passwords.encode("seller-password")));
    User other = users.save(new User("other-ph", "other-ph@example.com",
        passwords.encode("other-password")));
    User admin = new User("admin-ph", "admin-ph@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  /** Creates a listing through the API (the seed-point path) and returns its id. */
  private long createViaApi(String token, long priceCents, String status) throws Exception {
    String statusField = status == null ? "" : ",\"status\":\"" + status + "\"";
    String body = mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Priced bike\",\"description\":\"rides fine\","
                + "\"priceCents\":" + priceCents + statusField + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }

  private void patchPrice(long id, String token, long priceCents) throws Exception {
    mockMvc.perform(patch("/api/items/{id}", id)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":" + priceCents + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void createSeedsFirstPointWithPinnedKeySet() throws Exception {
    long id = createViaApi(sellerToken, 25000L, null);

    String body = mockMvc.perform(get("/api/items/{id}/price-history", id)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].priceCents").value(25000))
        .andReturn().getResponse().getContentAsString();

    // Key-set pin: a point is exactly price + timestamp — no row id,
    // no editor identity can leak into the public history contract.
    JsonNode point = objectMapper.readTree(body).path("data").path(0);
    Set<String> keys = new TreeSet<>();
    point.fieldNames().forEachRemaining(keys::add);
    assertThat(keys).isEqualTo(Set.of("priceCents", "changedAt"));
    assertThat(point.path("changedAt").asText()).isNotBlank();
    assertThat(historyRepo.findByItemIdOrderByChangedAtAscIdAsc(id)).hasSize(1);
  }

  @Test
  void priceEditAppendsButNoOpAndTitleOnlyEditsDoNot() throws Exception {
    long id = createViaApi(sellerToken, 25000L, null);

    patchPrice(id, sellerToken, 29000L);
    // No-op edit: the same price again appends nothing.
    patchPrice(id, sellerToken, 29000L);
    // Title-only edit: no price field at all, appends nothing.
    mockMvc.perform(patch("/api/items/{id}", id)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Priced bike — serviced\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/items/{id}/price-history", id)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        // Oldest first: the creation price precedes the edit's price.
        .andExpect(jsonPath("$.data[0].priceCents").value(25000))
        .andExpect(jsonPath("$.data[1].priceCents").value(29000));
  }

  @Test
  void statusFlipAndPhotoChangeNeverAppend() throws Exception {
    long id = createViaApi(sellerToken, 25000L, null);

    mockMvc.perform(multipart("/api/items/{id}/photo", id)
            .file(new MockMultipartFile("file", "bike.png", "image/png", PNG_BYTES))
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk());
    mockMvc.perform(patch("/api/items/{id}/status", id)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().isOk());

    // A SOLD listing's history stays readable (a sale happened at a
    // price — the timeline is exactly what a reader wants), and it
    // still holds only the creation point.
    mockMvc.perform(get("/api/items/{id}/price-history", id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].priceCents").value(25000));
  }

  @Test
  void otherUserReadsLiveListingHistory() throws Exception {
    long id = createViaApi(sellerToken, 25000L, null);
    patchPrice(id, sellerToken, 24000L);

    mockMvc.perform(get("/api/items/{id}/price-history", id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].priceCents").value(25000))
        .andExpect(jsonPath("$.data[1].priceCents").value(24000));
  }

  @Test
  void draftHistoryIs404ForOthersButReadableBySellerAndAdmin() throws Exception {
    long id = createViaApi(sellerToken, 25000L, "DRAFT");

    mockMvc.perform(get("/api/items/{id}/price-history", id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(get("/api/items/{id}/price-history", id)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].priceCents").value(25000));
    mockMvc.perform(get("/api/items/{id}/price-history", id)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
  }

  @Test
  void withdrawnHistoryIs404ForEveryone() throws Exception {
    Item item = new Item("Withdrawn bike", "gone", 25000L, seller.getId());
    item.setStatus(ItemStatus.WITHDRAWN);
    item = itemRepo.save(item);
    historyRepo.save(new ItemPriceHistory(item.getId(), 25000L, Instant.now()));

    mockMvc.perform(get("/api/items/{id}/price-history", item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/items/{id}/price-history", item.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void expiredHistoryIs404() throws Exception {
    Item item = new Item("Expired bike", "stale", 25000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item.setExpiresAt(Instant.now().minusSeconds(3600));
    item = itemRepo.save(item);
    historyRepo.save(new ItemPriceHistory(item.getId(), 25000L, Instant.now()));

    mockMvc.perform(get("/api/items/{id}/price-history", item.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void unknownIdIs404AndAnonymousIs401() throws Exception {
    mockMvc.perform(get("/api/items/{id}/price-history", 999999L)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mockMvc.perform(get("/api/items/{id}/price-history", 1L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
