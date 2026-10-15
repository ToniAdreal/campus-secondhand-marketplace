package com.toni.marketplace.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Price offers (backlog #168): the MockMvc state/authz matrix, the
 * one-live-offer rule, accept-creates-order-at-the-agreed-price, the
 * buy-now race pin, and the accept/decline notifications.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@code NotificationTest}: the rows must be committed by the requests
 * themselves to be asserted here (a second request must see the first
 * request's offer, order and notification rows).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceOfferTest {

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
    String tag = "offer-" + SEQ.incrementAndGet() + "-"
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

  @Test
  void createOffer_happyPath_pendingWithDeadline_visibleToBothSidesOnly() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller, 10_000L);

    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.itemId").value(item.getId()))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.sellerId").value(seller.getId()))
        .andExpect(jsonPath("$.data.priceCents").value(8000))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.orderId").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.data.expiresAt").exists());

    mockMvc.perform(get("/api/offers/made")
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1));
    mockMvc.perform(get("/api/offers/received")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1));
    // A third party sees neither side of the offer.
    mockMvc.perform(get("/api/offers/made")
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
    mockMvc.perform(get("/api/offers/received")
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(0));
  }

  @Test
  void createOffer_guards_ownListing422_notAvailable409_unknown404_anonymous401_badPrice400()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);

    // The seller cannot offer on their own listing.
    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8000}"))
        .andExpect(status().isUnprocessableEntity());
    // Unknown listing.
    mockMvc.perform(post("/api/items/{itemId}/offers", 999_999L)
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8000}"))
        .andExpect(status().isNotFound());
    // Anonymous.
    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8000}"))
        .andExpect(status().isUnauthorized());
    // Non-positive price is a validation failure.
    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":0}"))
        .andExpect(status().isBadRequest());
    // A RESERVED listing takes no offers.
    item.setStatus(ItemStatus.RESERVED);
    items.save(item);
    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8000}"))
        .andExpect(status().isConflict());
    assertThat(offers.count()).isZero();
  }

  @Test
  void secondLiveOffer_is409_withdrawFreesThePair() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 8_000L);

    mockMvc.perform(post("/api/items/{itemId}/offers", item.getId())
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"priceCents\":8500}"))
        .andExpect(status().isConflict());
    assertThat(offers.count()).isEqualTo(1);

    mockMvc.perform(post("/api/offers/{id}/withdraw", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));

    // After the withdraw the pair is free: a fresh offer succeeds.
    createOffer(buyer, item, 8_500L);
    assertThat(offers.count()).isEqualTo(2);
  }

  @Test
  void accept_createsOrderAtAgreedPrice_reservesListing_notifiesBuyer() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_500L);

    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.orderId").isNumber());

    PriceOffer offer = offers.findById(offerId).orElseThrow();
    assertThat(offer.getOrderId()).isNotNull();
    // The order snapshot is the AGREED price, never the asking price.
    assertThat(orders.findById(offer.getOrderId()).orElseThrow().getAmountCents())
        .isEqualTo(7_500L);
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.RESERVED);
    // Exactly one notification: the buyer, OFFER_ACCEPTED, with the order id.
    assertThat(notifications.findAll())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.getRecipientId()).isEqualTo(buyer.getId());
          assertThat(n.getType()).isEqualTo(NotificationType.OFFER_ACCEPTED);
          assertThat(n.getOfferId()).isEqualTo(offerId);
          assertThat(n.getOrderId()).isEqualTo(offer.getOrderId());
        });
  }

  @Test
  void accept_afterBuyNowWonTheRace_is409_offerStaysPending_singleOrder() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User racer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_500L);

    // A buy-now lands first: the listing reserves under an active order.
    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(racer))
            .header("Idempotency-Key", "race-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isConflict());
    // The failed accept rolled back atomically: offer still PENDING,
    // exactly one order exists (the racer's), nobody was notified.
    assertThat(offers.findById(offerId).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.PENDING);
    assertThat(orders.count()).isEqualTo(1);
    assertThat(notifications.count()).isZero();
  }

  @Test
  void decisions_areSideScoped_someoneElsesOfferIs404() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    User stranger = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 8_000L);

    // The buyer cannot accept/decline; a stranger cannot do anything.
    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/decline", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/withdraw", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/withdraw", offerId)
            .header("Authorization", "Bearer " + token(stranger)))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/api/offers/{id}/accept", 999_999L)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isNotFound());
    assertThat(offers.findById(offerId).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.PENDING);
  }

  @Test
  void decline_isTerminal_listingUntouched_noOrder_buyerNotifiedWithoutOrder() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 8_000L);

    mockMvc.perform(post("/api/offers/{id}/decline", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DECLINED"));

    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.AVAILABLE);
    assertThat(orders.count()).isZero();
    assertThat(notifications.findAll())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.getRecipientId()).isEqualTo(buyer.getId());
          assertThat(n.getType()).isEqualTo(NotificationType.OFFER_DECLINED);
          assertThat(n.getOfferId()).isEqualTo(offerId);
          assertThat(n.getOrderId()).isNull();
        });

    // Terminal: neither side can move the offer again.
    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity());
    mockMvc.perform(post("/api/offers/{id}/withdraw", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity());
    // Decline is final, but a FRESH offer is allowed (no counter chain).
    createOffer(buyer, item, 8_200L);
  }
}
