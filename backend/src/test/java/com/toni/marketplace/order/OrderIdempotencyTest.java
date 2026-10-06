package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.sql.Timestamp;
import java.time.Duration;
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
 * {@code POST /api/orders} idempotency contract.
 *
 * <p>Deliberately NOT {@code @Transactional}: the idempotency-key lifecycle
 * commits in its own transactions ({@code REQUIRES_NEW}), so a wrapping test
 * transaction would hide the key rows from the reservation path (and the key
 * rows would outlive the test's rollback anyway). Each test owns its data via
 * unique usernames and wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderIdempotencyTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository users;

  @Autowired
  private ItemRepository items;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private RefreshTokenRepository refreshTokens;

  @Autowired
  private PasswordService passwords;

  @Autowired
  private JwtTokenService jwt;

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    keys.deleteAll();
    orders.deleteAll();
    refreshTokens.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = role + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller, ItemStatus status) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(status);
    return items.save(item);
  }

  private String orderBody(long itemId) {
    return "{\"itemId\":" + itemId + "}";
  }

  private String key() {
    return "key-" + UUID.randomUUID();
  }

  @Test
  void firstRequestWithKeyCreatesOrder() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.itemId").value(item.getId()))
        .andExpect(jsonPath("$.data.buyerId").value(buyer.getId()))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.amountCents").value(88000));

    assertThat(orders.findAll()).hasSize(1);
    assertThat(keys.findAll())
        .hasSize(1)
        .first().satisfies(k -> {
          assertThat(k.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
          assertThat(k.getOrderId()).isEqualTo(orders.findAll().get(0).getId());
        });
  }

  @Test
  void retryWithSameKeyReturnsOriginalOrderWithoutDoubleCreate() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    String idemKey = key();

    String first = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.data.id")).longValue();

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(orderId));

    // The item is no longer AVAILABLE-guard-clean (an active order exists),
    // yet the replay must NOT re-run creation — exactly one order row.
    assertThat(orders.findAll()).hasSize(1);
    assertThat(keys.findAll()).hasSize(1);
  }

  @Test
  void sameKeyWithDifferentItemIsRejected422() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item itemA = newItem(seller, ItemStatus.AVAILABLE);
    Item itemB = newItem(seller, ItemStatus.AVAILABLE);
    String idemKey = key();

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(itemA.getId())))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(itemB.getId())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findAll()).hasSize(1);
  }

  @Test
  void missingIdempotencyKeyHeaderIs400() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(orders.findAll()).isEmpty();
  }

  @Test
  void blankAndTooLongKeysAre400() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    String tooLong = "k".repeat(65);

    for (String badKey : new String[]{"   ", tooLong}) {
      mockMvc.perform(post("/api/orders")
              .header("Authorization", "Bearer " + token(buyer))
              .header("Idempotency-Key", badKey)
              .contentType(MediaType.APPLICATION_JSON)
              .content(orderBody(item.getId())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value(400));
    }

    assertThat(orders.findAll()).isEmpty();
    assertThat(keys.findAll()).isEmpty();
  }

  @Test
  void unauthenticatedCreateIs401() throws Exception {
    User seller = newUser("seller");
    Item item = newItem(seller, ItemStatus.AVAILABLE);

    mockMvc.perform(post("/api/orders")
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(orders.findAll()).isEmpty();
  }

  @Test
  void unknownItemIs404() throws Exception {
    User buyer = newUser("buyer");

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(999999L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    assertThat(orders.findAll()).isEmpty();
  }

  @Test
  void orderingOwnListingIs422() throws Exception {
    User seller = newUser("seller");
    Item item = newItem(seller, ItemStatus.AVAILABLE);

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(seller))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findAll()).isEmpty();
  }

  @Test
  void unavailableItemIs409() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.SOLD);

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    assertThat(orders.findAll()).isEmpty();
  }

  @Test
  void secondBuyerOnOrderedItemIs409() throws Exception {
    User seller = newUser("seller");
    User buyerA = newUser("buyerA");
    User buyerB = newUser("buyerB");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    orders.save(new Order(item, buyerA.getId(), item.getPriceCents()));

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyerB))
            .header("Idempotency-Key", key())
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    assertThat(orders.findAll()).hasSize(1);
  }

  @Test
  void failedAttemptBurnsTheKey() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    String idemKey = key();

    IdempotencyKey burned = keys.save(new IdempotencyKey(buyer.getId(), idemKey, item.getId()));
    burned.fail();
    keys.save(burned);

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    assertThat(orders.findAll()).isEmpty();
  }

  @Test
  void concurrentInFlightRequestIs409() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    String idemKey = key();

    keys.save(new IdempotencyKey(buyer.getId(), idemKey, item.getId()));

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    assertThat(orders.findAll()).isEmpty();
  }

  @Test
  void staleInFlightKeyIsReclaimed() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    String idemKey = key();

    IdempotencyKey stale = keys.save(new IdempotencyKey(buyer.getId(), idemKey, item.getId()));
    // Simulate an owner that crashed 15 minutes ago between reserve and complete.
    jdbc.update("UPDATE idempotency_key SET updated_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(15))), stale.getId());

    mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", idemKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(item.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.itemId").value(item.getId()));

    assertThat(orders.findAll()).hasSize(1);
    assertThat(keys.findAll())
        .hasSize(1)
        .first().satisfies(k -> assertThat(k.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED));
  }
}
