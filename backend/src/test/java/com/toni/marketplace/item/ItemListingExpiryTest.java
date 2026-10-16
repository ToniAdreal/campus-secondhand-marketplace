package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.MutableClock;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.order.OrderCreationService;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Listing lifetime (backlog #177): creation stamps
 * {@code expiresAt = now + app.listings.ttl}; a past-deadline AVAILABLE
 * listing is gone on the public reads immediately (browse list excluded,
 * detail 404 — the documented choice) while the seller's own My-listings
 * view still shows it; the nightly {@link ListingExpiryService} flips
 * the stored status to EXPIRED and the two paths agree on the same row;
 * {@code POST /api/items/{id}/renew} (seller or ADMIN) resets the
 * deadline for expired or expiring listings and 422s everything else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // each test rolls back the users and items it creates
class ItemListingExpiryTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository itemRepo;

  @Autowired
  private ItemService itemService;

  @Autowired
  private OrderCreationService orderCreation;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  private User seller;
  private User other;
  private String sellerToken;
  private String otherToken;
  private String adminToken;

  @BeforeEach
  void createUsersAndTokens() {
    seller = users.save(new User("seller-exp", "seller-exp@example.com",
        passwords.encode("seller-password")));
    other = users.save(new User("other-exp", "other-exp@example.com",
        passwords.encode("other-password")));
    User admin = new User("admin-exp", "admin-exp@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);

    sellerToken = jwt.createTokenPair(seller).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private Item seedItem(String title, ItemStatus status, Instant expiresAt) {
    Item item = new Item(title, "a used thing", 2500L, seller.getId());
    item.setStatus(status);
    item.setExpiresAt(expiresAt);
    return itemRepo.save(item);
  }

  // --- creation stamps the deadline ---

  @Test
  void createListingStampsExpiryThirtyDaysOutByDefault() throws Exception {
    Instant before = Instant.now();
    mockMvc.perform(post("/api/items")
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fresh lamp\",\"description\":\"new\",\"priceCents\":2500}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.expiresAt").exists());

    Item saved = itemRepo.findAll().stream()
        .filter(i -> i.getTitle().equals("Fresh lamp")).findFirst().orElseThrow();
    assertThat(saved.getExpiresAt()).isNotNull();
    assertThat(saved.getExpiresAt()).isAfter(before.plus(Duration.ofDays(29)));
    assertThat(saved.getExpiresAt()).isBefore(Instant.now().plus(Duration.ofDays(31)));
  }

  // --- lazy-on-read: browse + detail treat expired as gone ---

  @Test
  void expiredListingIsExcludedFromBrowseButLiveOneIsNot() throws Exception {
    seedItem("Expired lamp", ItemStatus.AVAILABLE, Instant.now().minus(Duration.ofHours(1)));
    seedItem("Live lamp", ItemStatus.AVAILABLE, Instant.now().plus(Duration.ofDays(10)));

    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].title", hasItem("Live lamp")))
        .andExpect(jsonPath("$.data.content[*].title", not(hasItem("Expired lamp"))));
  }

  @Test
  void expiredListingDetailIs404LikeAnUnknownId() throws Exception {
    Item expired = seedItem("Expired lamp", ItemStatus.AVAILABLE,
        Instant.now().minus(Duration.ofMinutes(5)));

    mockMvc.perform(get("/api/items/{id}", expired.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void sellerMineListStillShowsExpiredListingWithItsDeadline() throws Exception {
    seedItem("Expired lamp", ItemStatus.AVAILABLE, Instant.now().minus(Duration.ofHours(2)));

    mockMvc.perform(get("/api/items").param("mine", "true")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].title", hasItem("Expired lamp")))
        .andExpect(jsonPath("$.data.content[0].expiresAt").exists());
  }

  // --- the nightly job flips the stored status; reads agree before and after ---

  @Test
  void expiryJobFlipsStoredStatusAndReadsAgreeBeforeAndAfter() throws Exception {
    Item expired = seedItem("Expired lamp", ItemStatus.AVAILABLE,
        Instant.now().minus(Duration.ofHours(1)));
    seedItem("Live lamp", ItemStatus.AVAILABLE, Instant.now().plus(Duration.ofDays(10)));

    // Before the job: the row is still stored AVAILABLE, yet the detail
    // read already treats it as gone (lazy-on-read).
    mockMvc.perform(get("/api/items/{id}", expired.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    MutableClock clock = new MutableClock(Instant.now());
    ListingExpiryService job = new ListingExpiryService(itemRepo, itemService, clock);
    ListingExpiryService.ExpiryCounts counts = job.expireStaleListings();
    assertThat(counts.expired()).isEqualTo(1);
    assertThat(counts.skipped()).isEqualTo(0);
    assertThat(itemRepo.findById(expired.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.EXPIRED);

    // After the job: the same reads give the same answers, and the
    // seller view now names the stored EXPIRED status.
    mockMvc.perform(get("/api/items/{id}", expired.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/items").header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].title", not(hasItem("Expired lamp"))));
    mockMvc.perform(get("/api/items").param("mine", "true")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[*].status", hasItem("EXPIRED")));
  }

  @Test
  void expireListingSkipsARowRenewedAfterTheScan() {
    Item item = seedItem("Renewed lamp", ItemStatus.AVAILABLE,
        Instant.now().minus(Duration.ofHours(1)));
    // The seller renewed between the job's worklist scan and the
    // per-row flip: the deadline moved into the future, so the flip
    // must skip the row instead of expiring a live listing.
    item.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
    itemRepo.save(item);

    assertThat(itemService.expireListing(item.getId(), Instant.now())).isFalse();
    assertThat(itemRepo.findById(item.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.AVAILABLE);
  }

  // --- renew ---

  @Test
  void sellerRenewsExpiredListingBackToAvailable() throws Exception {
    Item expired = seedItem("Expired lamp", ItemStatus.AVAILABLE,
        Instant.now().minus(Duration.ofHours(1)));

    mockMvc.perform(post("/api/items/{id}/renew", expired.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("AVAILABLE"));

    Item saved = itemRepo.findById(expired.getId()).orElseThrow();
    assertThat(saved.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));

    // And the detail read serves it again.
    mockMvc.perform(get("/api/items/{id}", expired.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk());
  }

  @Test
  void adminRenewsStoredExpiredListing() throws Exception {
    Item expired = seedItem("Expired lamp", ItemStatus.EXPIRED,
        Instant.now().minus(Duration.ofDays(1)));

    mockMvc.perform(post("/api/items/{id}/renew", expired.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
  }

  @Test
  void sellerRenewsListingExpiringInsideTheWindow() throws Exception {
    Item expiring = seedItem("Expiring lamp", ItemStatus.AVAILABLE,
        Instant.now().plus(Duration.ofDays(3)));

    mockMvc.perform(post("/api/items/{id}/renew", expiring.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk());
    assertThat(itemRepo.findById(expiring.getId()).orElseThrow().getExpiresAt())
        .isAfter(Instant.now().plus(Duration.ofDays(29)));
  }

  @Test
  void renewLiveListingWithDistantDeadlineIs422() throws Exception {
    Item live = seedItem("Live lamp", ItemStatus.AVAILABLE,
        Instant.now().plus(Duration.ofDays(25)));

    mockMvc.perform(post("/api/items/{id}/renew", live.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void renewSoldOrReservedListingIs422() throws Exception {
    Item sold = seedItem("Sold lamp", ItemStatus.SOLD,
        Instant.now().minus(Duration.ofDays(1)));
    Item reserved = seedItem("Reserved lamp", ItemStatus.RESERVED,
        Instant.now().minus(Duration.ofDays(1)));

    mockMvc.perform(post("/api/items/{id}/renew", sold.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/items/{id}/renew", reserved.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void renewAuthzMatrix() throws Exception {
    Item expired = seedItem("Expired lamp", ItemStatus.AVAILABLE,
        Instant.now().minus(Duration.ofHours(1)));

    mockMvc.perform(post("/api/items/{id}/renew", expired.getId())
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/items/{id}/renew", expired.getId()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/items/{id}/renew", 999999L)
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isNotFound());
  }

  // --- order creation refuses an expired listing (lazy definition) ---

  @Test
  void orderCreationRefusesExpiredListingWith409() {
    Item expired = seedItem("Expired lamp", ItemStatus.AVAILABLE,
        Instant.now().minus(Duration.ofHours(1)));

    assertThatThrownBy(() -> orderCreation.create(other.getId(), expired.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(itemRepo.findById(expired.getId())).get()
        .extracting(Item::getStatus).isEqualTo(ItemStatus.AVAILABLE);
  }
}
