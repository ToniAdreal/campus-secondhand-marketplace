package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recently-viewed listings (backlog #195): the detail GET records the
 * view in the caller's trail — only for an authenticated non-seller,
 * non-ADMIN caller (the #186 counting rule), never on list/browse
 * reads — a repeat view moves the listing to the front on a single
 * upserted row, and GET /api/users/me/recently-viewed returns the
 * caller's own rows, most-recent first, capped at
 * {@link RecentlyViewedService#RECENT_LIMIT}, silently omitting
 * listings that are no longer AVAILABLE.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecentlyViewedTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private RecentlyViewedRepository trail;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User seller;
  private String sellerToken;
  private String buyerToken;
  private String otherToken;
  private String adminToken;
  private Long buyerId;

  @BeforeEach
  void createUsersAndTokens() {
    String tag = "recent-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
    User buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    User other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    User admin = new User(tag + "-admin", tag + "-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    users.save(admin);
    buyerId = buyer.getId();
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    otherToken = jwt.createTokenPair(other).accessToken();
    adminToken = jwt.createTokenPair(admin).accessToken();
  }

  private Item seedItem(ItemStatus status) {
    Item item = new Item("Trail seed " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 4200L, seller.getId());
    item.setStatus(status);
    return itemRepo.save(item);
  }

  private void viewAsBuyer(Item item) throws Exception {
    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());
  }

  @Test
  void detailViewRecordsAndListShowsTheListing() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    viewAsBuyer(item);

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(item.getId()))
        .andExpect(jsonPath("$.data[0].status").value("AVAILABLE"))
        .andExpect(jsonPath("$.data[0].sellerUsername").value(seller.getUsername()));
    assertThat(trail.findByUserIdAndItemId(buyerId, item.getId())).isPresent();
  }

  @Test
  void repeatViewMovesToFrontOnASingleRow() throws Exception {
    Item first = seedItem(ItemStatus.AVAILABLE);
    Item second = seedItem(ItemStatus.AVAILABLE);

    viewAsBuyer(first);
    viewAsBuyer(second);
    viewAsBuyer(first);

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].id").value(first.getId()))
        .andExpect(jsonPath("$.data[1].id").value(second.getId()));
    // The repeat view upserted: still exactly one row per pair.
    assertThat(trail.findAll().stream()
        .filter(r -> r.getUserId().equals(buyerId)).count()).isEqualTo(2);
  }

  @Test
  void sellerViewsOfOwnListingAreNotRecorded() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + sellerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
    assertThat(trail.findByUserIdAndItemId(seller.getId(), item.getId())).isEmpty();
  }

  @Test
  void adminViewsAreNotRecorded() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(get("/api/items/" + item.getId())
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void browseReadsNeverRecord() throws Exception {
    seedItem(ItemStatus.AVAILABLE);

    mockMvc.perform(get("/api/items")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void soldListingIsOmittedFromTheTrail() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);
    viewAsBuyer(item);

    item.setStatus(ItemStatus.SOLD);
    itemRepo.save(item);

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
    // Omitted, not deleted: the trail row itself survives (it ages
    // out through the retention purge, not through status flips).
    assertThat(trail.findByUserIdAndItemId(buyerId, item.getId())).isPresent();
  }

  @Test
  void pastDeadlineListingIsOmittedFromTheTrail() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);
    viewAsBuyer(item);

    item.setExpiresAt(Instant.now().minusSeconds(60));
    itemRepo.save(item);

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void deletedListingIsOmittedFromTheTrail() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);
    viewAsBuyer(item);

    itemRepo.delete(item);
    itemRepo.flush();

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void trailsAreIsolatedBetweenUsers() throws Exception {
    Item item = seedItem(ItemStatus.AVAILABLE);
    viewAsBuyer(item);

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void anonymousCallersGet401() throws Exception {
    mockMvc.perform(get("/api/users/me/recently-viewed"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownListingDetailIs404AndRecordsNothing() throws Exception {
    mockMvc.perform(get("/api/items/999999")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isNotFound());

    assertThat(trail.findAll().stream()
        .filter(r -> r.getUserId().equals(buyerId)).count()).isZero();
  }

  @Test
  void listIsCappedAtTheDocumentedLimit() throws Exception {
    // Seed the trail directly: 25 rows with strictly increasing
    // viewed_at, so the expected front of the list is unambiguous.
    Item newest = null;
    for (int i = 0; i < 25; i++) {
      Item item = seedItem(ItemStatus.AVAILABLE);
      trail.save(new RecentlyViewed(buyerId, item.getId(),
          Instant.EPOCH.plusSeconds(1_000 + i)));
      newest = item;
    }

    mockMvc.perform(get("/api/users/me/recently-viewed")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()")
            .value(RecentlyViewedService.RECENT_LIMIT))
        .andExpect(jsonPath("$.data[0].id").value(newest.getId()));
  }
}
