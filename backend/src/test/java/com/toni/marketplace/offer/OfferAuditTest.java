package com.toni.marketplace.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLog;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
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
 * Offer-lifecycle audit rows (backlog #389): every negotiation outcome
 * writes exactly one row to the append-only trail from inside its own
 * transition's transaction, with the acting side as actor and the offer
 * row as target — accept, decline, counter, counter-accept,
 * counter-decline (same OFFER_DECLINED action, told apart by the actor)
 * and expiry (actor is the lapsing party, not the waiting party). The
 * ADMIN audit-log read picks the rows up through its existing filters.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring {@link
 * PriceOfferTest}: the rows must be committed by the requests and the
 * expiry job themselves to be asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfferAuditTest {

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
  private AuditLogRepository auditLog;

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
    auditLog.deleteAll();
    offers.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "offer-audit-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private User newAdmin() {
    String tag = "offer-audit-admin-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag, tag + "@example.com", passwords.encode("password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
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

  private List<AuditLog> rowsFor(long offerId) {
    return auditLog.findByTargetTypeAndTargetIdOrderByIdAsc(AuditTargetType.OFFER, offerId);
  }

  @Test
  void accept_writesOneOfferAcceptedRow_actorIsSeller() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);

    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    assertThat(rowsFor(offerId)).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_ACCEPTED);
      assertThat(row.getActorUserId()).isEqualTo(seller.getId());
      assertThat(row.getTargetType()).isEqualTo(AuditTargetType.OFFER);
      assertThat(row.getTargetId()).isEqualTo(offerId);
    });
  }

  @Test
  void reAccept_isRejectedAndWritesNoSecondRow() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);

    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isUnprocessableEntity());

    assertThat(rowsFor(offerId)).singleElement().satisfies(row ->
        assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_ACCEPTED));
  }

  @Test
  void decline_writesOneOfferDeclinedRow_actorIsSeller() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);

    mockMvc.perform(post("/api/offers/{id}/decline", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    assertThat(rowsFor(offerId)).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_DECLINED);
      assertThat(row.getActorUserId()).isEqualTo(seller.getId());
    });
  }

  @Test
  void counter_writesOneOfferCounteredRow_actorIsSeller() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);

    counter(seller, offerId, 9_000L);

    assertThat(rowsFor(offerId)).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_COUNTERED);
      assertThat(row.getActorUserId()).isEqualTo(seller.getId());
    });
  }

  @Test
  void counterAccept_writesOneOfferCounterAcceptedRow_actorIsBuyer() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);

    mockMvc.perform(post("/api/offers/{id}/counter-accept", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    // The counter row stays; the accept appends exactly one row.
    assertThat(rowsFor(offerId)).hasSize(2);
    assertThat(rowsFor(offerId).get(1)).satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_COUNTER_ACCEPTED);
      assertThat(row.getActorUserId()).isEqualTo(buyer.getId());
    });
  }

  @Test
  void counterDecline_writesOfferDeclinedRow_actorIsBuyer() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);

    mockMvc.perform(post("/api/offers/{id}/counter-decline", offerId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());

    // The counter row stays; the decline appends exactly one row, told
    // apart from a seller decline by the buyer actor.
    assertThat(rowsFor(offerId)).hasSize(2);
    assertThat(rowsFor(offerId).get(1)).satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_DECLINED);
      assertThat(row.getActorUserId()).isEqualTo(buyer.getId());
    });
  }

  @Test
  void pendingExpiry_writesOneOfferExpiredRow_actorIsLapsingSeller_rerunSilent()
      throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    backdateDeadline(offerId);

    OfferExpiryService.ExpiryCounts counts = expiryService.expireStaleOffers();
    assertThat(counts.expired()).isEqualTo(1);

    // The seller let the offer lapse, so the row attributes the expiry
    // to the seller; the buyer is the waiting party, not the actor.
    assertThat(rowsFor(offerId)).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_EXPIRED);
      assertThat(row.getActorUserId()).isEqualTo(seller.getId());
    });

    OfferExpiryService.ExpiryCounts rerun = expiryService.expireStaleOffers();
    assertThat(rerun.expired()).isZero();
    assertThat(rowsFor(offerId)).hasSize(1);
  }

  @Test
  void counteredExpiry_writesOneOfferExpiredRow_actorIsLapsingBuyer() throws Exception {
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    counter(seller, offerId, 9_000L);
    auditLog.deleteAll();
    backdateDeadline(offerId);

    OfferExpiryService.ExpiryCounts counts = expiryService.expireStaleOffers();
    assertThat(counts.expired()).isEqualTo(1);

    // The buyer let the counter lapse, so the row attributes the expiry
    // to the buyer; the seller is the waiting party, not the actor.
    assertThat(rowsFor(offerId)).singleElement().satisfies(row -> {
      assertThat(row.getAction()).isEqualTo(AuditAction.OFFER_EXPIRED);
      assertThat(row.getActorUserId()).isEqualTo(buyer.getId());
    });
  }

  @Test
  void adminAuditLogRead_returnsOfferRowsViaExistingFilters() throws Exception {
    User admin = newAdmin();
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller, 10_000L);
    long offerId = createOffer(buyer, item, 7_000L);
    mockMvc.perform(post("/api/offers/{id}/accept", offerId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/admin/audit-log")
            .header("Authorization", "Bearer " + token(admin))
            .param("action", "OFFER_ACCEPTED")
            .param("targetType", "OFFER")
            .param("targetId", String.valueOf(offerId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.content[0].action").value("OFFER_ACCEPTED"))
        .andExpect(jsonPath("$.data.content[0].actorUserId").value(seller.getId()))
        .andExpect(jsonPath("$.data.content[0].targetType").value("OFFER"))
        .andExpect(jsonPath("$.data.content[0].targetId").value(offerId));
  }
}
