package com.toni.marketplace.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * Counter-offers (backlog #193): the counter bound/state/authz matrix,
 * counter-accept creating the order at the COUNTER price, the buy-now
 * race pin, counter-decline finality, and expiry of COUNTERED rows.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring {@link
 * PriceOfferTest}: the rows must be committed by the requests
 * themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfferCounterTest {

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
    offers.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "counter-" + SEQ.incrementAndGet() + "-"
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

  @Test
  void counter_happyPath_counteredWithPrice_buyerPriceKept_buyerNotified() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);

    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":9000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COUNTERED"))
        .andExpect(jsonPath("$.data.priceCents").value(7000))
        .andExpect(jsonPath("$.data.counterPriceCents").value(9000))
        .andExpect(jsonPath("$.data.orderId").value(org.hamcrest.Matchers.nullValue()));

    // The listing is untouched — a counter reserves nothing.
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
    assertThat(orders.count()).isZero();
    assertThat(notifications.findAll())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.getRecipientId()).isEqualTo(buyer.getId());
          assertThat(n.getType()).isEqualTo(NotificationType.OFFER_COUNTERED);
          assertThat(n.getOfferId()).isEqualTo(offerId);
          assertThat(n.getOrderId()).isNull();
        });
  }

  @Test
  void counter_bounds_atOrBelowOffer422_atOrAboveAsking422_badPrice400() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);

    // At the buyer's price the seller should simply accept.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":7000}"))
        .andExpect(status().isUnprocessableEntity());
    // Below the buyer's price is not a counter.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":6500}"))
        .andExpect(status().isUnprocessableEntity());
    // At the asking price the buyer should simply buy now.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":10000}"))
        .andExpect(status().isUnprocessableEntity());
    // Above the asking price is not a counter either.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":11000}"))
        .andExpect(status().isUnprocessableEntity());
    // Non-positive is a validation failure, like offer creation.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":0}"))
        .andExpect(status().isBadRequest());

    // Every rejected counter left the offer exactly as it was.
    PriceOffer offer = offers.findById(offerId).orElseThrow();
    assertThat(offer.getStatus()).isEqualTo(OfferStatus.PENDING);
    assertThat(offer.getCounterPriceCents()).isNull();
    assertThat(notifications.count()).isZero();
  }

  @Test
  void counter_isSellerScoped_andOnePerOffer() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);

    // Only the listing's seller can counter; anyone else gets 404.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":9000}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(stranger))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":9000}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/counter", 999_999L)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":9000}"))
        .andExpect(status().isNotFound());
    // Anonymous.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":9000}"))
        .andExpect(status().isUnauthorized());

    counter(seller, offerId, 9_000L);
    // One counter per offer: a second counter is 422, and the seller's
    // own accept/decline no longer apply — the decision is the buyer's.
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8500}"))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/offers/{id}/decline", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity());
    // The buyer withdraws PENDING offers only; a counter is answered
    // with counter-decline, so withdraw on COUNTERED is 422 too.
    mockMvc.perform(post("/api/offers/{id}/withdraw", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());
    // The first counter stands, untouched by every rejected attempt.
    PriceOffer offer = offers.findById(offerId).orElseThrow();
    assertThat(offer.getStatus()).isEqualTo(OfferStatus.COUNTERED);
    assertThat(offer.getCounterPriceCents()).isEqualTo(9_000L);
  }

  @Test
  void counteredOffer_stillHoldsTheLivePair_secondCreateIs409() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);

    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8000}"))
        .andExpect(status().isConflict());
    assertThat(offers.count()).isEqualTo(1);
  }

  @Test
  void counterAccept_createsOrderAtCounterPrice_reservesListing_notifiesSeller()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);
    notifications.deleteAll();

    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.counterPriceCents").value(9000))
        .andExpect(jsonPath("$.data.orderId").isNumber());

    PriceOffer offer = offers.findById(offerId).orElseThrow();
    assertThat(offer.getOrderId()).isNotNull();
    // The order snapshot is the COUNTER price — not the buyer's offer,
    // not the asking price.
    assertThat(orders.findById(offer.getOrderId()).orElseThrow().getAmountCents())
        .isEqualTo(9_000L);
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    assertThat(notifications.findAll())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.getRecipientId()).isEqualTo(seller.getId());
          assertThat(n.getType()).isEqualTo(NotificationType.OFFER_COUNTER_ACCEPTED);
          assertThat(n.getOfferId()).isEqualTo(offerId);
          assertThat(n.getOrderId()).isEqualTo(offer.getOrderId());
        });

    // Terminal: no second answer is possible on either counter endpoint.
    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/offers/{id}/counter-decline", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());
    assertThat(orders.count()).isEqualTo(1);
  }

  @Test
  void counterAccept_afterBuyNowWonTheRace_is409_offerStaysCountered_singleOrder()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User racer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);
    notifications.deleteAll();

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(racer))
            .header("Idempotency-Key", "counter-race-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isConflict());
    // The failed accept rolled back atomically: offer still COUNTERED
    // at its counter price, exactly one order (the racer's), and the
    // seller was not told a counter was accepted.
    PriceOffer offer = offers.findById(offerId).orElseThrow();
    assertThat(offer.getStatus()).isEqualTo(OfferStatus.COUNTERED);
    assertThat(offer.getCounterPriceCents()).isEqualTo(9_000L);
    assertThat(offer.getOrderId()).isNull();
    assertThat(orders.count()).isEqualTo(1);
    assertThat(notifications.count()).isZero();
  }

  @Test
  void counterDecline_isTerminal_listingUntouched_noOrder_sellerNotified_freshOfferAllowed()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);
    notifications.deleteAll();

    mockMvc.perform(post("/api/offers/{id}/counter-decline", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DECLINED"))
        .andExpect(jsonPath("$.data.counterPriceCents").value(9000));

    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
    assertThat(orders.count()).isZero();
    assertThat(notifications.findAll())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.getRecipientId()).isEqualTo(seller.getId());
          assertThat(n.getType()).isEqualTo(NotificationType.OFFER_COUNTER_DECLINED);
          assertThat(n.getOfferId()).isEqualTo(offerId);
          assertThat(n.getOrderId()).isNull();
        });

    // Terminal for both sides — but the freed pair allows a fresh offer.
    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/offers/{id}/counter", offerId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":9500}"))
        .andExpect(status().isUnprocessableEntity());
    createOffer(buyer, item, 7_500L);
    assertThat(offers.count()).isEqualTo(2);
  }

  @Test
  void counterDecisions_areBuyerScoped_sellerAndStrangerGet404() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);

    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/counter-decline", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/counter-decline", 999_999L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
    assertThat(offers.findById(offerId).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.COUNTERED);
  }

  @Test
  void counteredOffer_expiresOnTheSameDeadline_jobSweepsIt_lazyCheckPinsIt()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);

    // Move the deadline into the past: countering bought no extra time.
    jdbc.update("UPDATE price_offer SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), offerId);

    // The lazy check on the buyer's decision endpoint refuses the stale
    // counter first: 422, and no order is created in the gap before the
    // expiry job's next run.
    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());
    assertThat(orders.count()).isZero();

    // A second stale COUNTERED offer (on another listing — the first
    // still holds its live pair until the job runs) joins it, and the
    // expiry job sweeps both COUNTERED rows to EXPIRED.
    Item item2 = newItem(seller, 10_000L);
    long offerId2 = createOffer(buyer, item2, 7_200L);
    counter(seller, offerId2, 9_200L);
    jdbc.update("UPDATE price_offer SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(60)), offerId2);
    OfferExpiryService.ExpiryCounts counts = expiryService.expireStaleOffers();
    assertThat(counts.expired()).isEqualTo(2);
    assertThat(offers.findById(offerId).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.EXPIRED);
    assertThat(offers.findById(offerId2).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.EXPIRED);
    // Expiry freed the pair: a fresh offer succeeds.
    createOffer(buyer, item, 7_400L);
  }
}
