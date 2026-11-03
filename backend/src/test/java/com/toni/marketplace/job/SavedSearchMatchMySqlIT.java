package com.toni.marketplace.job;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Saved-search background matching against a real MySQL 8
 * (Testcontainers). Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test phase.
 *
 * <p>Backlog #321: the H2 suite ({@code SavedSearchMatchIntegrationTest})
 * proves the matcher against the LIKE browse path, but production
 * browse on MySQL goes through the full-text path ({@code MATCH(title,
 * description) AGAINST (? IN NATURAL LANGUAGE MODE)}), so the matcher
 * semantics need pinning on the dialect they actually run against:
 * the matcher pages the browse projection, fires exactly one batched
 * {@code SAVED_SEARCH_MATCH} notification per saved search per run
 * for listings created after the watermark, advances the watermark
 * in the same transaction, ignores listings created before the
 * watermark, and a second run with no new listings fires nothing.
 *
 * <p>Keyword choice is dialect load-bearing, not incidental: the
 * H2 suite's hyphenated tags (e.g. {@code sm-1-abcd1234}) would be
 * tokenised by MySQL full-text into short fragments below the
 * token-size floor, so this class uses one plain six-letter token,
 * {@code quokka}, present in each matching title and in no other
 * seeded row. No matcher semantics were changed to fit MySQL.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code SavedSearchMatchIntegrationTest}: the matcher must commit
 * its own transaction for the assertions below to see its rows.
 */
@Testcontainers
@SpringBootTest
class SavedSearchMatchMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Full-text token shared by every matching listing in this class. */
  private static final String KEYWORD = "quokka";

  @Autowired private SavedSearchMatchingService matcher;
  @Autowired private SavedSearchRepository savedSearches;
  @Autowired private NotificationRepository notifications;
  @Autowired private ItemRepository items;
  @Autowired private UserRepository users;

  private User seller;
  private User buyer;

  @BeforeEach
  void createUsers() {
    String tag = "ssm-" + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com", "hash"));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com", "hash"));
  }

  @AfterEach
  void cleanUp() {
    // FK-safe order: notifications reference saved searches, saved
    // searches reference users, items reference sellers by plain id.
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
  private SavedSearch baselinedSearch(Long userId) {
    SavedSearch search = new SavedSearch(userId, KEYWORD, null, null, null, "newest");
    search.advanceLastNotifiedItemId(items.findMaxId());
    return savedSearches.save(search);
  }

  private List<Notification> notificationsFor(User user) {
    return notifications.findByRecipientId(user.getId(), PageRequest.of(0, 50)).getContent();
  }

  @Test
  void newListingsAfterWatermarkAreOneBatchedNotificationAndRepeatRunIsSilent() {
    SavedSearch search = baselinedSearch(buyer.getId());
    Item first = seedItem(KEYWORD + " bike 0", seller, ItemStatus.AVAILABLE);
    Item second = seedItem(KEYWORD + " bike 1", seller, ItemStatus.AVAILABLE);
    Item third = seedItem(KEYWORD + " bike 2", seller, ItemStatus.AVAILABLE);
    seedItem("plain desk lamp", seller, ItemStatus.AVAILABLE);

    SavedSearchMatchingService.MatchCounts counts = matcher.matchSavedSearches();

    assertThat(counts.notifications()).isEqualTo(1);
    assertThat(counts.newMatches()).isEqualTo(3);
    List<Notification> rows = notificationsFor(buyer);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getType()).isEqualTo(NotificationType.SAVED_SEARCH_MATCH);
    assertThat(rows.get(0).getSavedSearchId()).isEqualTo(search.getId());
    assertThat(rows.get(0).getMatchedListingCount()).isEqualTo(3);
    // The watermark advances over the matched listings in the same
    // transaction: the unrelated listing is not in this search's
    // projection, so it is not "seen" by this search.
    assertThat(savedSearches.findById(search.getId()).orElseThrow()
        .getLastNotifiedItemId()).isEqualTo(third.getId());
    assertThat(first.getId()).isLessThan(second.getId());

    // A repeat run over unchanged listings advances nothing and
    // notifies nothing — every candidate id is at/below the watermark.
    SavedSearchMatchingService.MatchCounts repeat = matcher.matchSavedSearches();
    assertThat(repeat.notifications()).isEqualTo(0);
    assertThat(notificationsFor(buyer)).hasSize(1);
    assertThat(savedSearches.findById(search.getId()).orElseThrow()
        .getLastNotifiedItemId()).isEqualTo(third.getId());
  }

  @Test
  void listingsBeforeWatermarkAreIgnoredButLaterListingsStillAlert() {
    seedItem(KEYWORD + " already here 0", seller, ItemStatus.AVAILABLE);
    seedItem(KEYWORD + " already here 1", seller, ItemStatus.AVAILABLE);
    SavedSearch search = baselinedSearch(buyer.getId());
    long baseline = search.getLastNotifiedItemId();

    matcher.matchSavedSearches();

    assertThat(notificationsFor(buyer)).isEmpty();
    assertThat(savedSearches.findById(search.getId()).orElseThrow()
        .getLastNotifiedItemId()).isEqualTo(baseline);

    seedItem(KEYWORD + " genuinely new", seller, ItemStatus.AVAILABLE);
    matcher.matchSavedSearches();

    List<Notification> rows = notificationsFor(buyer);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getMatchedListingCount()).isEqualTo(1);
  }

  @Test
  void reservedAndSoldListingsAdvanceWatermarkWithoutNotifyingOnMysql() {
    SavedSearch search = baselinedSearch(buyer.getId());
    Item reserved = seedItem(KEYWORD + " reserved", seller, ItemStatus.RESERVED);
    Item sold = seedItem(KEYWORD + " sold", seller, ItemStatus.SOLD);

    matcher.matchSavedSearches();

    // Dialect pin: the MySQL full-text projection still carries
    // RESERVED/SOLD rows (the status filter is matcher-side, not
    // query-side), so both rows are "seen" — but neither alerts.
    assertThat(notificationsFor(buyer)).isEmpty();
    assertThat(savedSearches.findById(search.getId()).orElseThrow()
        .getLastNotifiedItemId()).isEqualTo(Math.max(reserved.getId(), sold.getId()));
  }
}
