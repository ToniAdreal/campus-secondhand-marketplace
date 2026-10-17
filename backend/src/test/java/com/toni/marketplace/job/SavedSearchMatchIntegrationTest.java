package com.toni.marketplace.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.item.SavedSearch;
import com.toni.marketplace.item.SavedSearchRepository;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Saved-search background matching (backlog #182): the scheduled
 * matcher in {@code job/} pages the SAME browse projection the read
 * path uses, fires ONE batched SAVED_SEARCH_MATCH notification per
 * saved search per run for listings created after the search's
 * watermark, and advances the watermark in the same transaction.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code NotificationTest}: the matcher must commit its own
 * transaction for the assertions below to see its rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SavedSearchMatchIntegrationTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private SavedSearchMatchingService matcher;
  @Autowired private SavedSearchRepository savedSearches;
  @Autowired private NotificationRepository notifications;
  @Autowired private ItemRepository items;
  @Autowired private UserRepository users;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User seller;
  private User buyer;
  private User other;
  private String buyerToken;
  private String tag;

  @BeforeEach
  void createUsers() {
    tag = "sm-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    other = users.save(new User(tag + "-other", tag + "-other@example.com",
        passwords.encode("other-password")));
    buyerToken = jwt.createTokenPair(buyer).accessToken();
  }

  @AfterEach
  void cleanUp() {
    notifications.deleteAll();
    savedSearches.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private Item seedItem(String title, User itemSeller, ItemStatus status) {
    Item item = new Item(title, "seeded", 5000L, itemSeller.getId());
    item.setStatus(status);
    return items.save(item);
  }

  /** A search whose watermark is exactly the current highest listing id. */
  private SavedSearch baselinedSearch(Long userId, String keyword) {
    SavedSearch search = new SavedSearch(userId, keyword, null, null, null, "newest");
    search.advanceLastNotifiedItemId(items.findMaxId());
    return savedSearches.save(search);
  }

  private List<Notification> notificationsFor(User user) {
    return notifications.findByRecipientId(user.getId(), PageRequest.of(0, 50)).getContent();
  }

  @Test
  void fiveNewMatchesAreOneBatchedNotificationAndARepeatRunIsSilent() {
    SavedSearch search = baselinedSearch(buyer.getId(), tag);
    for (int i = 0; i < 5; i++) {
      seedItem(tag + " bike " + i, seller, ItemStatus.AVAILABLE);
    }
    seedItem("unrelated thing", seller, ItemStatus.AVAILABLE);

    matcher.matchSavedSearches();

    List<Notification> rows = notificationsFor(buyer);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getType()).isEqualTo(NotificationType.SAVED_SEARCH_MATCH);
    assertThat(rows.get(0).getSavedSearchId()).isEqualTo(search.getId());
    assertThat(rows.get(0).getMatchedListingCount()).isEqualTo(5);
    assertThat(rows.get(0).getOrderId()).isNull();
    assertThat(rows.get(0).getOfferId()).isNull();

    // A repeat run over unchanged listings advances nothing and
    // notifies nothing — every candidate id is at/below the watermark.
    matcher.matchSavedSearches();
    assertThat(notificationsFor(buyer)).hasSize(1);
  }

  @Test
  void reservedAndSoldListingsNeverMatchButStillAdvanceTheWatermarkAsSeen() {
    SavedSearch search = baselinedSearch(buyer.getId(), tag);
    Item reserved = seedItem(tag + " reserved", seller, ItemStatus.RESERVED);
    Item sold = seedItem(tag + " sold", seller, ItemStatus.SOLD);

    matcher.matchSavedSearches();

    assertThat(notificationsFor(buyer)).isEmpty();
    SavedSearch reloaded = savedSearches.findById(search.getId()).orElseThrow();
    assertThat(reloaded.getLastNotifiedItemId())
        .isEqualTo(Math.max(reserved.getId(), sold.getId()));
  }

  @Test
  void ownListingsNeverMatchOnesOwnSearchAndSearchesNeverCrossUsers() {
    baselinedSearch(buyer.getId(), tag);
    SavedSearch otherSearch = baselinedSearch(other.getId(), tag);
    // The only new listing is the buyer's OWN listing.
    seedItem(tag + " own listing", buyer, ItemStatus.AVAILABLE);

    matcher.matchSavedSearches();

    assertThat(notificationsFor(buyer)).isEmpty();
    List<Notification> otherRows = notificationsFor(other);
    assertThat(otherRows).hasSize(1);
    assertThat(otherRows.get(0).getSavedSearchId()).isEqualTo(otherSearch.getId());
    assertThat(otherRows.get(0).getMatchedListingCount()).isEqualTo(1);
  }

  @Test
  void aLegacyRowWithNoWatermarkIsBaselinedSilentlyOnItsFirstRun() {
    Item existing = seedItem(tag + " already here", seller, ItemStatus.AVAILABLE);
    SavedSearch legacy = savedSearches.save(
        new SavedSearch(buyer.getId(), tag, null, null, null, "newest"));
    assertThat(legacy.getLastNotifiedItemId()).isNull();

    matcher.matchSavedSearches();

    assertThat(notificationsFor(buyer)).isEmpty();
    assertThat(savedSearches.findById(legacy.getId()).orElseThrow()
        .getLastNotifiedItemId()).isEqualTo(existing.getId());

    seedItem(tag + " genuinely new", seller, ItemStatus.AVAILABLE);
    matcher.matchSavedSearches();
    assertThat(notificationsFor(buyer)).hasSize(1);
  }

  @Test
  void anApiCreatedSearchIsBaselinedAtCreationSoOnlyLaterListingsAlert() throws Exception {
    seedItem(tag + " before the search", seller, ItemStatus.AVAILABLE);

    mockMvc.perform(post("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"keyword\":\"" + tag + "\"}"))
        .andExpect(status().isOk());

    matcher.matchSavedSearches();
    assertThat(notificationsFor(buyer)).isEmpty();

    seedItem(tag + " after the search", seller, ItemStatus.AVAILABLE);
    matcher.matchSavedSearches();
    assertThat(notificationsFor(buyer)).hasSize(1);
  }

  @Test
  void theNotificationReadsBackThroughTheApiAndTheWatermarkIsNeverExposed() throws Exception {
    SavedSearch search = baselinedSearch(buyer.getId(), tag);
    seedItem(tag + " match", seller, ItemStatus.AVAILABLE);
    matcher.matchSavedSearches();

    mockMvc.perform(get("/api/notifications")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].type").value("SAVED_SEARCH_MATCH"))
        .andExpect(jsonPath("$.data.content[0].savedSearchId").value(search.getId()))
        .andExpect(jsonPath("$.data.content[0].matchedListingCount").value(1))
        .andExpect(jsonPath("$.data.content[0].orderId").value(
            org.hamcrest.Matchers.nullValue()));

    // The saved-search DTO carries the filters + live count only:
    // the watermark is matcher bookkeeping, never user data.
    String body = mockMvc.perform(get("/api/users/me/saved-searches")
            .header("Authorization", "Bearer " + buyerToken))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("lastNotified");
  }

  @Test
  void deletingTheSearchStopsFutureMatches() {
    SavedSearch search = baselinedSearch(buyer.getId(), tag);
    seedItem(tag + " first", seller, ItemStatus.AVAILABLE);
    matcher.matchSavedSearches();
    assertThat(notificationsFor(buyer)).hasSize(1);

    savedSearches.delete(search);
    seedItem(tag + " second", seller, ItemStatus.AVAILABLE);
    matcher.matchSavedSearches();

    assertThat(notificationsFor(buyer)).hasSize(1);
  }
}
