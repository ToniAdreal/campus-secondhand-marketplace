package com.toni.marketplace.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationPreference;
import com.toni.marketplace.notification.NotificationPreferenceRepository;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.notification.NotificationType;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Offer-expiry notifications (backlog #226): the expiry seam notifies
 * the WAITING party only — the buyer on a PENDING expiry, the seller
 * on a COUNTERED expiry — carrying the offer id, exactly once, and
 * honouring a muted OFFER_EXPIRED preference (backlog #216). Accept
 * and decline paths write their own rows and no OFFER_EXPIRED row.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring {@link
 * OfferCounterTest}: the rows must be committed by the requests and
 * the expiry job themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfferExpiryNotificationTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PriceOfferRepository offers;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private NotificationPreferenceRepository preferences;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private OfferExpiryService expiryService;

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    notifications.deleteAll();
    preferences.deleteAll();
    offers.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "expiry-notify-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller, long priceCents) {
    Item item = new Item("Film camera", "Canon AE-1", priceCents, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  private long createOffer(User buyer, Item item, long priceCents) throws Exception {
    String body = mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":" + priceCents + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private void counter(User seller, long offerId, long priceCents) throws Exception {
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":" + priceCents + "}"))
        .andExpect(status().isOk());
  }

  private void backdateDeadline(long offerId) {
    jdbc.update("UPDATE price_offer SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), offerId);
  }

  @Test
  void pendingExpiry_notifiesBuyerOnly_carriesOfferId_sweepReRunWritesNoSecondRow()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    backdateDeadline(offerId);

    OfferExpiryService.ExpiryCounts counts = expiryService.expireStaleOffers();
    assertThat(counts.expired()).isEqualTo(1);
    assertThat(offers.findById(offerId).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.EXPIRED);

    // Exactly one row, to the buyer — the seller, who let it lapse,
    // already knows and gets nothing.
    assertThat(notifications.findAll())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.getRecipientId()).isEqualTo(buyer.getId());
          assertThat(n.getType()).isEqualTo(NotificationType.OFFER_EXPIRED);
          assertThat(n.getOfferId()).isEqualTo(offerId);
          assertThat(n.getOrderId()).isNull();
        });

    // The sweep re-run finds the row EXPIRED and writes no second row.
    OfferExpiryService.ExpiryCounts rerun = expiryService.expireStaleOffers();
    assertThat(rerun.expired()).isZero();
    assertThat(notifications.count()).isEqualTo(1);
  }

  @Test
  void counteredExpiry_notifiesSellerOnly_carriesOfferId() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);
    notifications.deleteAll();
    backdateDeadline(offerId);

    OfferExpiryService.ExpiryCounts counts = expiryService.expireStaleOffers();
    assertThat(counts.expired()).isEqualTo(1);
    assertThat(offers.findById(offerId).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.EXPIRED);

    // The counter's seller waited on the buyer's answer; the buyer,
    // who let the counter lapse, gets nothing.
    assertThat(notifications.findAll())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.getRecipientId()).isEqualTo(seller.getId());
          assertThat(n.getType()).isEqualTo(NotificationType.OFFER_EXPIRED);
          assertThat(n.getOfferId()).isEqualTo(offerId);
          assertThat(n.getOrderId()).isNull();
        });
  }

  @Test
  void acceptAndDecline_writeTheirOwnRows_andNoOfferExpiredRow() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long acceptedId = createOffer(buyer, item, 7_000L);
    mockMvc.perform(post("/api/offers/{id}/accept", acceptedId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    Item item2 = newItem(seller, 10_000L);
    long declinedId = createOffer(buyer, item2, 7_100L);
    mockMvc.perform(post("/api/offers/{id}/decline", declinedId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    assertThat(notifications.findAll())
        .extracting(n -> n.getType())
        .containsExactlyInAnyOrder(
            NotificationType.OFFER_ACCEPTED, NotificationType.OFFER_DECLINED);
  }

  @Test
  void mutedOfferExpiredPreference_expiryWritesNoRow() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    preferences.save(new NotificationPreference(
        buyer.getId(), NotificationType.OFFER_EXPIRED, false, Instant.now()));
    backdateDeadline(offerId);

    OfferExpiryService.ExpiryCounts counts = expiryService.expireStaleOffers();
    assertThat(counts.expired()).isEqualTo(1);
    assertThat(offers.findById(offerId).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.EXPIRED);
    // The row still flips — only the notification is suppressed.
    assertThat(notifications.count()).isZero();
  }
}
