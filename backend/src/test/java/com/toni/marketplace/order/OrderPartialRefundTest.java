package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.RefreshTokenRepository;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Partial refunds (backlog #210): POST /api/orders/{id}/refund accepts an
 * optional {@code amountCents}. Each partial goes through the PSP seam
 * with its own sequence-derived idempotency key; the cumulative
 * {@code refundedAmountCents} is the state that matters — the order stays
 * PAID (listing RESERVED) while the total is below the order amount and
 * flips to REFUNDED (listing released) exactly when it reaches it.
 * Over-amount is a 422 BEFORE any PSP call, and two concurrent partials
 * can never push the cumulative total past the order amount.
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderRefundTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()} — the concurrency test
 * needs real commits to race on.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPartialRefundTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final long PRICE = 88_000L;

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
  private PaymentService payments;

  @Autowired
  private OrderService orderService;

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

  private User newAdmin() {
    User admin = newUser("admin");
    admin.setRoles(EnumSet.of(Role.ADMIN));
    return users.save(admin);
  }

  private String token(User user) {
    return jwt.createTokenPair(user).accessToken();
  }

  private Item newItem(User seller) {
    Item item = new Item("Film camera", "Canon AE-1", PRICE, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
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

  private long paidOrder(User seller, User buyer, Item item) throws Exception {
    long orderId = createOrder(buyer, item);
    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"));
    return orderId;
  }

  private void refund(User caller, long orderId, String body) throws Exception {
    var request = post("/api/orders/{id}/refund", orderId)
        .header("Authorization", "Bearer " + token(caller));
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    mockMvc.perform(request)
        .andExpect(status().isOk());
  }

  @Test
  void twoPartialsSummingToFull_flipToRefundedAndReleaseListing() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = paidOrder(seller, buyer, item);
    long pspBefore = payments.refundsIssued();

    // First partial: 30_000 of 88_000 — money moves, nothing transitions.
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountCents\":30000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.refundedAmountCents").value(30_000));
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
      assertThat(o.getRefundedAmountCents()).isEqualTo(30_000L);
      assertThat(o.getRefundCount()).isEqualTo(1);
      assertThat(o.getRefundIdempotencyKey()).isEqualTo("rfd_" + orderId + "_1");
    });
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
    String firstRefundId = orders.findById(orderId).orElseThrow().getRefundId();
    assertThat(firstRefundId).startsWith("rfd_mock_");

    // Second partial: the remaining 58_000 — the cumulative total reaches
    // the order amount, so the order flips and the listing is released.
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountCents\":58000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REFUNDED"))
        .andExpect(jsonPath("$.data.refundedAmountCents").value(88_000));
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED);
      assertThat(o.getRefundedAmountCents()).isEqualTo(88_000L);
      assertThat(o.getRefundCount()).isEqualTo(2);
      // Each partial carried its own sequence-derived key — never reused.
      assertThat(o.getRefundIdempotencyKey()).isEqualTo("rfd_" + orderId + "_2");
      assertThat(o.getRefundId()).startsWith("rfd_mock_");
      assertThat(o.getRefundId()).isNotEqualTo(firstRefundId);
    });
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.AVAILABLE));
    // Two partials = two logical PSP refunds, no more.
    assertThat(payments.refundsIssued() - pspBefore).isEqualTo(2);
  }

  @Test
  void absentAmountAfterPartial_refundsTheRemainder() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = paidOrder(seller, buyer, item);

    refund(seller, orderId, "{\"amountCents\":8000}");
    // No body at all — the pre-#210 call shape — refunds the remainder.
    refund(seller, orderId, null);

    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.REFUNDED);
      assertThat(o.getRefundedAmountCents()).isEqualTo(PRICE);
      assertThat(o.getRefundCount()).isEqualTo(2);
    });
  }

  @Test
  void overAmount_is422BeforeAnyPspCall() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = paidOrder(seller, buyer, item);
    long pspBefore = payments.refundsIssued();

    // More than the whole order amount.
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountCents\":88001}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    // A valid partial, then an amount over the REMAINING balance.
    refund(seller, orderId, "{\"amountCents\":80000}");
    mockMvc.perform(post("/api/orders/{id}/refund", orderId)
            .header("Authorization", "Bearer " + token(seller))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountCents\":8001}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));

    // Only the valid partial ever reached the seam.
    assertThat(payments.refundsIssued() - pspBefore).isEqualTo(1);
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
      assertThat(o.getRefundedAmountCents()).isEqualTo(80_000L);
    });
  }

  @Test
  void nonPositiveAmount_is422BeforeAnyPspCall() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = paidOrder(seller, buyer, item);
    long pspBefore = payments.refundsIssued();

    for (String body : List.of("{\"amountCents\":0}", "{\"amountCents\":-500}")) {
      mockMvc.perform(post("/api/orders/{id}/refund", orderId)
              .header("Authorization", "Bearer " + token(seller))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value(422));
    }
    assertThat(payments.refundsIssued() - pspBefore).isZero();
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getRefundedAmountCents()).isZero());
  }

  @Test
  void settlementCountsPartiallyRefundedPaidOrder() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User admin = newAdmin();
    Item item = newItem(seller);
    long orderId = paidOrder(seller, buyer, item);

    refund(seller, orderId, "{\"amountCents\":30000}");

    // The order is still PAID — a status-only refunded total would report
    // 0. The settlement summary sums refundedAmountCents instead (#210).
    mockMvc.perform(get("/api/admin/payments/settlement")
            .header("Authorization", "Bearer " + token(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.capturedCents").value(88_000))
        .andExpect(jsonPath("$.data.refundedCents").value(30_000))
        .andExpect(jsonPath("$.data.netCents").value(58_000))
        .andExpect(jsonPath("$.data.paidCount").value(1))
        .andExpect(jsonPath("$.data.refundedCount").value(0));
  }

  @Test
  void concurrentPartials_cumulativeNeverExceedsAmount() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller);
    long orderId = paidOrder(seller, buyer, item);
    long pspBefore = payments.refundsIssued();
    // Each racer wants 60_000 of the 88_000 order — both together would
    // over-refund. Exactly one may land; the other must lose cleanly
    // (409 version race, or 422 once the winner's committed balance
    // shrinks the remainder below its amount). Never a 5xx, never an
    // over-refund.
    long partial = 60_000L;
    int racers = 2;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HttpStatus>> futures = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      futures.add(pool.submit(() -> {
        start.await();
        try {
          orderService.refund(seller.getId(), false, orderId, partial);
          return HttpStatus.OK;
        } catch (ResponseStatusException e) {
          return HttpStatus.valueOf(e.getStatusCode().value());
        }
      }));
    }
    start.countDown();
    List<HttpStatus> outcomes = new ArrayList<>();
    for (Future<HttpStatus> future : futures) {
      outcomes.add(future.get(60, TimeUnit.SECONDS));
    }
    pool.shutdownNow();

    assertThat(outcomes).filteredOn(h -> h == HttpStatus.OK).hasSize(1);
    assertThat(outcomes).filteredOn(h ->
        h == HttpStatus.CONFLICT || h == HttpStatus.UNPROCESSABLE_ENTITY).hasSize(1);
    assertThat(orders.findById(orderId)).hasValueSatisfying(o -> {
      assertThat(o.getRefundedAmountCents()).isEqualTo(partial);
      assertThat(o.getRefundedAmountCents()).isLessThanOrEqualTo(o.getAmountCents());
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
      assertThat(o.getRefundCount()).isEqualTo(1);
    });
    // The PSP counter is deliberately NOT pinned to exactly 1 here
    // (the sequential tests above pin exact counts where they are
    // deterministic). The loser calls the PSP seam before its
    // @Version check fails at flush — the documented #45/#65 caveat —
    // with the SAME deterministic sequence key and the SAME amount:
    // if that call lands before the winner forgets the key (#124) the
    // mock replays it (delta 1); if it lands after the forget, the
    // mock counts a second issuance (delta 2) whose transaction then
    // rolls back, leaving refundCount at 1 above. A real PSP dedupes
    // the repeated key server-side, so no second logical refund
    // exists in either interleaving — the committed row is the pin.
    assertThat(payments.refundsIssued() - pspBefore).isBetween(1L, 2L);
  }
}
