package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listing drafts (backlog #198): a listing created with
 * {@code status=DRAFT} is seller-private — the public browse/search
 * reads exclude it, another user's detail read answers 404 (never 403,
 * so the draft's existence does not leak), an anonymous detail read
 * answers 401 from the security chain (the detail route requires a
 * token for every listing), and the owner reads it 200.
 * {@code POST /api/items/{id}/publish} flips DRAFT to AVAILABLE and
 * starts the #177 expiry clock at publish time; a draft carries no
 * deadline and never expires. A draft is never orderable (409 from the
 * order path's existing AVAILABLE check).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListingDraftTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private com.toni.marketplace.order.OrderCreationService orderCreation;

  private User seller;
  private String sellerToken;
  private String otherToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-draft", "seller-draft@example.com",
        passwords.encode("seller-password")));
    User other = users.save(new User("other-draft", "other-draft@example.com",
        passwords.encode("other-password")));
    User admin = new User("admin-draft", "admin-draft@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    sellerToken = jwt.createTokenPair(seller).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private long create(String token, String title, String statusJson) throws Exception {
    String statusField = statusJson == null ? "" : ",\"status\":\"" + statusJson + "\"";
    String body = mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"" + title + "\",\"description\":\"draft desc\","
                + "\"priceCents\":2500" + statusField + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode root = objectMapper.readTree(body);
    return root.path("data").path("id").asLong();
  }

  // --- create ---

  @Test
  void createWithDraftStatusCreatesDraftWithoutExpiryDeadline() throws Exception {
    long id = create(sellerToken, "Draft bike", "DRAFT");

    Item saved = itemRepo.findById(id).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(ItemStatus.DRAFT);
    // Drafts never expire: no deadline is stamped at create — the #177
    // clock starts at publish.
    assertThat(saved.getExpiresAt()).isNull();

    mockMvc.perform(get("/api/items/" + id)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DRAFT"))
        .andExpect(jsonPath("$.data.expiresAt").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void createWithoutStatusStillCreatesAvailableWithDeadline() throws Exception {
    long id = create(sellerToken, "Live bike", null);

    Item saved = itemRepo.findById(id).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
    assertThat(saved.getExpiresAt()).isNotNull();
  }

  @Test
  void createWithSoldStatusIs422() throws Exception {
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Sneaky\",\"description\":\"x\",\"priceCents\":100,"
                + "\"status\":\"SOLD\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
    assertThat(itemRepo.findAll()).isEmpty();
  }

  // --- visibility matrix ---

  @Test
  void draftDetailVisibilityMatrix() throws Exception {
    long draftId = create(sellerToken, "Secret draft", "DRAFT");

    // Owner: 200.
    mockMvc.perform(get("/api/items/" + draftId)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("Secret draft"));
    // ADMIN: 200 (moderation read, mirroring the edit ownership rule).
    mockMvc.perform(get("/api/items/" + draftId)
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    // Other user: 404, not 403 — the draft's existence must not leak.
    mockMvc.perform(get("/api/items/" + draftId)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    // Anonymous: 401 from the security chain (detail requires a token).
    mockMvc.perform(get("/api/items/" + draftId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void draftIsAbsentFromPublicBrowseAndSearchButPresentInMine() throws Exception {
    create(sellerToken, "Draft unicycle", "DRAFT");
    create(sellerToken, "Live unicycle", null);

    // Public browse (authenticated other user): only the live row.
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[*].title", hasItem("Live unicycle")))
        .andExpect(jsonPath("$.data.content[*].title", not(hasItem("Draft unicycle"))));
    // Public search for the draft's keyword: no hit.
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + otherToken)
            .param("q", "Draft unicycle"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    // Even the seller's own public browse excludes the draft...
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].title", not(hasItem("Draft unicycle"))));
    // ...while the seller's My-listings view includes it.
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + sellerToken)
            .param("mine", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(2))
        .andExpect(jsonPath("$.data.content[*].title", hasItem("Draft unicycle")))
        .andExpect(jsonPath("$.data.content[*].status", hasItem("DRAFT")));
  }

  @Test
  void draftIsAbsentFromSellerStorefront() throws Exception {
    create(sellerToken, "Storefront draft", "DRAFT");

    mockMvc.perform(get("/api/users/" + seller.getId() + "/listings")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  // --- publish ---

  @Test
  void publishFlipsDraftToAvailableAndStartsExpiryClockAtPublish() throws Exception {
    long id = create(sellerToken, "Publish me", "DRAFT");
    Instant before = Instant.now();

    mockMvc.perform(post("/api/items/" + id + "/publish")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.data.expiresAt").exists());

    Item saved = itemRepo.findById(id).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
    // The deadline is publish + TTL (default 30 days) — after the publish
    // moment, not anchored at the draft's creation.
    assertThat(saved.getExpiresAt()).isNotNull();
    assertThat(saved.getExpiresAt()).isAfter(before.plus(Duration.ofDays(29)));
    assertThat(saved.getExpiresAt()).isBefore(Instant.now().plus(Duration.ofDays(31)));

    // And the now-live listing is visible to everyone.
    mockMvc.perform(get("/api/items/" + id)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].title", hasItem("Publish me")));
  }

  @Test
  void publishNonDraftIs422() throws Exception {
    long liveId = create(sellerToken, "Already live", null);

    mockMvc.perform(post("/api/items/" + liveId + "/publish")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
  }

  @Test
  void publishOwnershipMatrix() throws Exception {
    long draftId = create(sellerToken, "Owned draft", "DRAFT");

    // Non-owner: 403 (the edit-endpoint ownership convention).
    mockMvc.perform(post("/api/items/" + draftId + "/publish")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isForbidden());
    // Anonymous: 401.
    mockMvc.perform(post("/api/items/" + draftId + "/publish"))
        .andExpect(status().isUnauthorized());
    // Unknown id: 404 (thrown by ItemSecurity before the ownership check).
    mockMvc.perform(post("/api/items/999999/publish")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound());
    // The failed attempts left the draft untouched.
    assertThat(itemRepo.findById(draftId).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.DRAFT);
  }

  // --- edit ---

  @Test
  void sellerEditsDraftFieldsAndPrice() throws Exception {
    long id = create(sellerToken, "Draft to edit", "DRAFT");

    mockMvc.perform(patch("/api/items/" + id)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Edited draft\",\"priceCents\":9900}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("Edited draft"))
        .andExpect(jsonPath("$.data.priceCents").value(9900))
        .andExpect(jsonPath("$.data.status").value("DRAFT"));
  }

  @Test
  void patchCanMoveAvailableToDraftAndDraftToAvailable() throws Exception {
    long id = create(sellerToken, "Toggle bike", null);

    // AVAILABLE -> DRAFT clears the deadline.
    mockMvc.perform(patch("/api/items/" + id)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DRAFT\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DRAFT"));
    assertThat(itemRepo.findById(id).orElseThrow().getExpiresAt()).isNull();

    // DRAFT -> AVAILABLE via PATCH stamps a fresh deadline (publish path
    // remains POST /publish; both use the same publish-time stamping).
    mockMvc.perform(patch("/api/items/" + id)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"AVAILABLE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
    assertThat(itemRepo.findById(id).orElseThrow().getExpiresAt()).isNotNull();
  }

  @Test
  void patchWithSoldStatusIs422() throws Exception {
    long id = create(sellerToken, "No sold via edit", "DRAFT");

    mockMvc.perform(patch("/api/items/" + id)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
    assertThat(itemRepo.findById(id).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.DRAFT);
  }

  // --- not orderable ---

  /**
   * A draft is never orderable: {@link OrderCreationService}'s existing
   * AVAILABLE check answers 409 ("item is not available"). Pinned at
   * the service seam rather than over HTTP on purpose: the HTTP order
   * path reserves its idempotency key in a REQUIRES_NEW transaction,
   * which cannot see this test's uncommitted rows (the reason the
   * order HTTP suites are non-transactional) — the service call joins
   * the test transaction and exercises exactly the guard the HTTP path
   * delegates to.
   */
  @Test
  void draftIsNotOrderable409() throws Exception {
    long draftId = create(sellerToken, "Unbuyable draft", "DRAFT");
    User buyer = users.findAll().stream()
        .filter(u -> u.getUsername().equals("other-draft")).findFirst().orElseThrow();

    assertThatThrownBy(() -> orderCreation.create(buyer.getId(), draftId))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(
            ((org.springframework.web.server.ResponseStatusException) e)
                .getStatusCode().value()).isEqualTo(409));
    assertThat(itemRepo.findById(draftId).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.DRAFT);
  }
}
