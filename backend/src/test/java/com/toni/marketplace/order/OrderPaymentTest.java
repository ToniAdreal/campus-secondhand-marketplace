package com.toni.marketplace.order;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/orders/{id}/pay} contract (mock PSP — no real money).
 *
 * <p>Deliberately NOT {@code @Transactional}, mirroring
 * {@link OrderIdempotencyTest}: each test owns its data via unique users and
 * wipes the touched tables in {@code cleanUp()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPaymentTest {

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

  private long payStatus(User payer, long orderId) throws Exception {
    String body = mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(payer)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.data.status")).isEqualTo("PAID");
    return ((Number) JsonPath.read(body, "$.data.id")).longValue();
  }

  @Test
  void buyerPays_transitionsToPaidAndReservesItem() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.id").value(orderId))
        .andExpect(jsonPath("$.data.status").value("PAID"))
        .andExpect(jsonPath("$.data.amountCents").value(88000));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
    // PAID is still an active order: the item keeps its active-order guard.
    assertThat(orders.findAll()).hasSize(1);
  }

  @Test
  void doublePay_isIdempotent_noDoubleCharge() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    long first = payStatus(buyer, orderId);
    long second = payStatus(buyer, orderId);

    assertThat(first).isEqualTo(orderId);
    assertThat(second).isEqualTo(orderId);
    assertThat(orders.findAll()).hasSize(1);
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
  }

  @Test
  void nonBuyerPay_isRejected403_orderStaysPending() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    User intruder = newUser("intruder");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(intruder)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING));
  }

  @Test
  void sellerPay_isRejected403() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(seller)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousPay_isRejected401() throws Exception {
    mockMvc.perform(post("/api/orders/{id}/pay", 12345L))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  void payUnknownOrder_is404() throws Exception {
    User buyer = newUser("buyer");

    mockMvc.perform(post("/api/orders/{id}/pay", 987654321L)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  void payCancelledOrder_isRejected422() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);
    orders.findById(orderId).ifPresent(o -> {
      o.setStatus(OrderStatus.CANCELLED);
      orders.save(o);
    });

    mockMvc.perform(post("/api/orders/{id}/pay", orderId)
            .header("Authorization", "Bearer " + token(buyer)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(422));
  }

  /**
   * Concurrent pays on one PENDING order: exactly one capture may win, but
   * every outcome the API can produce (200 PAID, or 409 version conflict) is
   * acceptable — a 5xx or a duplicated/confused terminal state is not.
   */
  @Test
  void concurrentPays_neverCorruptAndNever5xx() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Item item = newItem(seller, ItemStatus.AVAILABLE);
    long orderId = createOrder(buyer, item);

    int threads = 4;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger conflict = new AtomicInteger();
    AtomicInteger serverError = new AtomicInteger();
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(() -> {
          try {
            start.await();
            int code = mockMvc.perform(post("/api/orders/{id}/pay", orderId)
                    .header("Authorization", "Bearer " + token(buyer)))
                .andReturn().getResponse().getStatus();
            if (code == 200) {
              ok.incrementAndGet();
            } else if (code == 409) {
              conflict.incrementAndGet();
            } else {
              serverError.incrementAndGet();
            }
          } catch (Exception e) {
            serverError.incrementAndGet();
          }
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(serverError.get()).isZero();
    assertThat(ok.get() + conflict.get()).isEqualTo(threads);
    assertThat(orders.findAll()).hasSize(1);
    assertThat(orders.findById(orderId)).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    assertThat(items.findById(item.getId())).hasValueSatisfying(i ->
        assertThat(i.getStatus()).isEqualTo(ItemStatus.RESERVED));
  }
}
