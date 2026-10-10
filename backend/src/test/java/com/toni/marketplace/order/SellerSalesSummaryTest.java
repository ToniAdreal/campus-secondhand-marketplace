package com.toni.marketplace.order;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
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
 * {@code GET /api/seller/orders/summary} contract (backlog #108) — the
 * aggregate mirror of {@link SellerOrderReadTest}.
 *
 * <p>Pinned here:
 * <ul>
 *   <li>counts by status over the caller's listings only, and gross =
 *       the {@code amountCents} snapshots of PAID + COMPLETED orders
 *       (REFUNDED excluded — its capture was handed back);</li>
 *   <li>a later price edit never rewrites the historical gross;</li>
 *   <li>the current-month window excludes an order backdated (via JDBC —
 *       {@code @PrePersist} owns {@code created_at}, so the API cannot
 *       produce an old order) to before this month's first instant, in
 *       the server-local zone the summary documents;</li>
 *   <li>a user with no listings gets zeros, not an error; anonymous
 *       callers get the 401 envelope.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link SellerOrderReadTest}: each test owns its data via unique users
 * and wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerSalesSummaryTest {

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

  private User newUser(String tag) {
    String name = tag + "-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(name, name + "@example.com", passwords.encode("password")));
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller, long priceCents) {
    return items.save(new Item("Film camera", "Canon AE-1", priceCents, seller.getId()));
  }

  private long createOrder(User buyer, Item item) throws Exception {
    String body = mockMvc.perform(post("/api/orders")
            .header("Authorization", "Bearer " + token(buyer))
            .header("Idempotency-Key", "key-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"itemId\":" + item.getId() + "}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  private void pay(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
  }

  private void complete(User seller, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/complete", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
  }

  private void refund(User seller, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk());
  }

  private void cancel(User buyer, long orderId) throws Exception {
    mockMvc.perform(post("/api/orders/{id}/cancel", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk());
  }

  @Test
  void summary_countsByStatus_grossIsPaidPlusCompletedSnapshots_refundedExcluded()
      throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");

    createOrder(buyer, newItem(seller, 10_000L)); // stays PENDING
    long paid = createOrder(buyer, newItem(seller, 20_000L));
    pay(buyer, paid); // PAID
    long completed = createOrder(buyer, newItem(seller, 30_000L));
    pay(buyer, completed);
    complete(seller, completed); // COMPLETED
    long refunded = createOrder(buyer, newItem(seller, 40_000L));
    pay(buyer, refunded);
    refund(seller, refunded); // REFUNDED — gross must NOT include it
    long cancelled = createOrder(buyer, newItem(seller, 50_000L));
    cancel(buyer, cancelled); // CANCELLED

    YearMonth thisMonth = YearMonth.now(ZoneId.systemDefault());
    mockMvc.perform(get("/api/seller/orders/summary")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalOrders").value(5))
        .andExpect(jsonPath("$.data.pendingCount").value(1))
        .andExpect(jsonPath("$.data.paidCount").value(1))
        .andExpect(jsonPath("$.data.completedCount").value(1))
        .andExpect(jsonPath("$.data.cancelledCount").value(1))
        .andExpect(jsonPath("$.data.refundedCount").value(1))
        // 20_000 (PAID) + 30_000 (COMPLETED); REFUNDED/PENDING/CANCELLED add 0.
        .andExpect(jsonPath("$.data.grossCents").value(50_000))
        .andExpect(jsonPath("$.data.currentMonth.year").value(thisMonth.getYear()))
        .andExpect(jsonPath("$.data.currentMonth.month").value(thisMonth.getMonthValue()))
        .andExpect(jsonPath("$.data.currentMonth.totalOrders").value(5))
        .andExpect(jsonPath("$.data.currentMonth.completedCount").value(1))
        .andExpect(jsonPath("$.data.currentMonth.grossCents").value(50_000));
  }

  @Test
  void summary_isScopedToOwnListingsOnly() throws Exception {
    User sellerA = newUser("sellerA");
    User sellerB = newUser("sellerB");
    User buyer = newUser("buyer");
    long ownPaid = createOrder(buyer, newItem(sellerA, 11_000L));
    pay(buyer, ownPaid);
    long otherPaid = createOrder(buyer, newItem(sellerB, 99_000L));
    pay(buyer, otherPaid);

    mockMvc.perform(get("/api/seller/orders/summary")
            .header("Authorization", "Bearer " + token(sellerA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalOrders").value(1))
        .andExpect(jsonPath("$.data.paidCount").value(1))
        .andExpect(jsonPath("$.data.grossCents").value(11_000));
  }

  @Test
  void summary_userWithNoListings_getsZerosNotAnError() throws Exception {
    User buyerOnly = newUser("buyerOnly");

    mockMvc.perform(get("/api/seller/orders/summary")
            .header("Authorization", "Bearer " + token(buyerOnly)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalOrders").value(0))
        .andExpect(jsonPath("$.data.completedCount").value(0))
        .andExpect(jsonPath("$.data.grossCents").value(0))
        .andExpect(jsonPath("$.data.currentMonth.totalOrders").value(0))
        .andExpect(jsonPath("$.data.currentMonth.grossCents").value(0));
  }

  @Test
  void summary_priceEditAfterOrder_doesNotChangeHistoricalGross() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, 88_000L);
    long orderId = createOrder(buyer, item);
    pay(buyer, orderId);

    // Rewrite the listing's price row directly: the API locks the price
    // on a RESERVED listing (422), but the summary's contract is about
    // the order row's snapshot, so any later price value — however it
    // got there — must leave the gross at the snapshotted 88_000.
    Item reloaded = items.findById(item.getId()).orElseThrow();
    reloaded.setPriceCents(1L);
    items.save(reloaded);

    mockMvc.perform(get("/api/seller/orders/summary")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.grossCents").value(88_000));
  }

  @Test
  void summary_previousMonthOrder_countsAllTimeButNotCurrentMonth() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    long oldOrder = createOrder(buyer, newItem(seller, 70_000L));
    pay(buyer, oldOrder);
    long freshOrder = createOrder(buyer, newItem(seller, 5_000L));
    pay(buyer, freshOrder);

    // Backdate the first order to one second before this month began
    // (server-local zone — the zone the summary documents).
    Instant monthStart = YearMonth.now(ZoneId.systemDefault())
        .atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    jdbc.update("update orders set created_at = ? where id = ?",
        Timestamp.from(monthStart.minusSeconds(1)), oldOrder);

    mockMvc.perform(get("/api/seller/orders/summary")
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalOrders").value(2))
        .andExpect(jsonPath("$.data.grossCents").value(75_000))
        .andExpect(jsonPath("$.data.currentMonth.totalOrders").value(1))
        .andExpect(jsonPath("$.data.currentMonth.grossCents").value(5_000));
  }

  @Test
  void summary_anonymous_isRejected401() throws Exception {
    mockMvc.perform(get("/api/seller/orders/summary"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
