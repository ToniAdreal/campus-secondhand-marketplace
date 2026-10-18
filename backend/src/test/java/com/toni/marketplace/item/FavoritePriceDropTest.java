package com.toni.marketplace.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.notification.Notification;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Price-drop notifications for saved listings (backlog #189): saving a
 * listing snapshots its price (Flyway V49); a price edit that lowers
 * the price writes exactly one FAVORITE_PRICE_DROP row per favoriter
 * whose snapshot is above the new price — carrying the snapshot as the
 * old price — and advances the snapshot in the same transaction, so a
 * deeper second drop notifies again while a rise, a same-price edit
 * and a drop that stays above the snapshot stay silent. The seller is
 * never notified, and unsaving stops future drops.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FavoritePriceDropTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository itemRepo;
  @Autowired private FavoriteRepository favorites;
  @Autowired private NotificationRepository notifications;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;

  private User seller;
  private String sellerToken;
  private User buyer;
  private String buyerToken;
  private User lateBuyer;
  private String lateBuyerToken;

  @BeforeEach
  void createUsersAndTokens() {
    String tag = "drop-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    lateBuyer = users.save(new User(tag + "-late", tag + "-late@example.com",
        passwords.encode("late-password")));
    sellerToken = jwt.createTokenPair(seller).accessToken();
    buyerToken = jwt.createTokenPair(buyer).accessToken();
    lateBuyerToken = jwt.createTokenPair(lateBuyer).accessToken();
  }

  private Item seedItem(long priceCents) {
    Item item = new Item("Price drop seed " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", priceCents, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return itemRepo.save(item);
  }

  private void favorite(String token, Long itemId) throws Exception {
    mockMvc.perform(put("/api/users/me/favorites/{itemId}", itemId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  private void unfavorite(String token, Long itemId) throws Exception {
    mockMvc.perform(delete("/api/users/me/favorites/{itemId}", itemId)
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  private void editPrice(Long itemId, long priceCents) throws Exception {
    mockMvc.perform(patch("/api/items/{id}", itemId)
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":" + priceCents + "}"))
        .andExpect(status().isOk());
  }

  private List<Notification> dropsFor(Long recipientId) {
    return notifications.findByRecipientId(recipientId, PageRequest.of(0, 50)).getContent()
        .stream().filter(n -> n.getType() == NotificationType.FAVORITE_PRICE_DROP).toList();
  }

  private Long snapshotOf(Long userId, Long itemId) {
    return favorites.findByItemId(itemId).stream()
        .filter(f -> f.getUserId().equals(userId))
        .findFirst().orElseThrow().getPriceSnapshotCents();
  }

  @Test
  void favoriteSnapshotsTheCurrentPrice() throws Exception {
    Item item = seedItem(10_000L);
    favorite(buyerToken, item.getId());
    assertThat(snapshotOf(buyer.getId(), item.getId())).isEqualTo(10_000L);
  }

  @Test
  void dropNotifiesOnceWithSnapshotAndNewPriceThenAdvances() throws Exception {
    Item item = seedItem(10_000L);
    favorite(buyerToken, item.getId());

    editPrice(item.getId(), 8_000L);

    List<Notification> drops = dropsFor(buyer.getId());
    assertThat(drops).hasSize(1);
    assertThat(drops.get(0).getItemId()).isEqualTo(item.getId());
    assertThat(drops.get(0).getOldPriceCents()).isEqualTo(10_000L);
    assertThat(drops.get(0).getNewPriceCents()).isEqualTo(8_000L);
    assertThat(snapshotOf(buyer.getId(), item.getId())).isEqualTo(8_000L);

    // A deeper second drop notifies again, from the advanced snapshot.
    editPrice(item.getId(), 6_000L);
    drops = dropsFor(buyer.getId());
    assertThat(drops).hasSize(2);
    assertThat(drops.stream().map(Notification::getOldPriceCents))
        .containsExactlyInAnyOrder(10_000L, 8_000L);
    assertThat(snapshotOf(buyer.getId(), item.getId())).isEqualTo(6_000L);
  }

  @Test
  void riseSamePriceAndDropAboveSnapshotStaySilent() throws Exception {
    Item item = seedItem(10_000L);
    favorite(buyerToken, item.getId());
    editPrice(item.getId(), 6_000L);
    assertThat(dropsFor(buyer.getId())).hasSize(1);

    // Rise: never notifies, and the snapshot does not move up.
    editPrice(item.getId(), 9_000L);
    assertThat(dropsFor(buyer.getId())).hasSize(1);
    assertThat(snapshotOf(buyer.getId(), item.getId())).isEqualTo(6_000L);

    // A drop that stays above the snapshot is not a drop for this
    // favoriter (they saved at 10000 but were last baselined at 6000).
    editPrice(item.getId(), 8_500L);
    assertThat(dropsFor(buyer.getId())).hasSize(1);

    // Same-price edit: the price did not move at all.
    editPrice(item.getId(), 8_500L);
    assertThat(dropsFor(buyer.getId())).hasSize(1);

    // Dropping below the snapshot notifies from the snapshot, not
    // from the intermediate 8500.
    editPrice(item.getId(), 5_000L);
    List<Notification> drops = dropsFor(buyer.getId());
    assertThat(drops).hasSize(2);
    assertThat(drops.stream().map(Notification::getNewPriceCents))
        .containsExactlyInAnyOrder(6_000L, 5_000L);
    assertThat(drops.stream()
        .filter(n -> n.getNewPriceCents() == 5_000L)
        .findFirst().orElseThrow().getOldPriceCents()).isEqualTo(6_000L);
  }

  @Test
  void sellerIsNeverNotifiedAboutTheirOwnListing() throws Exception {
    Item item = seedItem(10_000L);
    favorite(sellerToken, item.getId());
    favorite(buyerToken, item.getId());

    editPrice(item.getId(), 7_000L);

    assertThat(dropsFor(seller.getId())).isEmpty();
    assertThat(dropsFor(buyer.getId())).hasSize(1);
  }

  @Test
  void unfavoriteStopsFutureDrops() throws Exception {
    Item item = seedItem(10_000L);
    favorite(buyerToken, item.getId());
    unfavorite(buyerToken, item.getId());

    editPrice(item.getId(), 7_000L);

    assertThat(dropsFor(buyer.getId())).isEmpty();
  }

  @Test
  void lateFavoriteBaselinesAtThePriceWhenSaved() throws Exception {
    Item item = seedItem(10_000L);
    editPrice(item.getId(), 8_000L);
    favorite(lateBuyerToken, item.getId());
    assertThat(snapshotOf(lateBuyer.getId(), item.getId())).isEqualTo(8_000L);

    editPrice(item.getId(), 7_500L);
    List<Notification> drops = dropsFor(lateBuyer.getId());
    assertThat(drops).hasSize(1);
    assertThat(drops.get(0).getOldPriceCents()).isEqualTo(8_000L);
    assertThat(drops.get(0).getNewPriceCents()).isEqualTo(7_500L);
  }

  @Test
  void nonPriceEditNotifiesNobody() throws Exception {
    Item item = seedItem(10_000L);
    favorite(buyerToken, item.getId());

    mockMvc.perform(patch("/api/items/{id}", item.getId())
            .header("Authorization", "Bearer " + sellerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"A clearer title\"}"))
        .andExpect(status().isOk());

    assertThat(dropsFor(buyer.getId())).isEmpty();
    assertThat(snapshotOf(buyer.getId(), item.getId())).isEqualTo(10_000L);
  }
}
