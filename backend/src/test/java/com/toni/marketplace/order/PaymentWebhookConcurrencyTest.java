package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Concurrent duplicate webhook deliveries on H2 (backlog #161) — the
 * unit-suite half of the race proof; {@code PaymentWebhookMySqlIT}
 * proves the same contract against real MySQL 8 in CI.
 *
 * <p>Two threads deliver the SAME signed event on one latch. The
 * contract is timing-invariant, so the assertions hold whichever
 * interleaving a round lands in: if the loser arrives after the
 * winner committed, it replays through the ledger lookup; if both
 * miss the lookup, the Flyway V28 unique key arbitrates the insert
 * and the loser replays through {@link WebhookEventRecorder}'s
 * failed-transaction re-read. Either way: both deliveries answer 200,
 * exactly one carries {@code duplicate=false}, exactly one
 * {@code webhook_event} row exists, and neither answer is a 5xx.
 *
 * <p>That last clause is the regression this class pins: the pre-#161
 * receiver caught the duplicate-key error inside the same transaction
 * that had flushed the insert, so the race loser answered 500 with an
 * {@code UnexpectedRollbackException} (reproduced with exactly this
 * latch setup during the #161 work). Multiple rounds per run give
 * the insert race repeated chances to actually occur — a single
 * round can serialize on a fast machine and pass vacuously.
 *
 * <p>Not {@code @Transactional}: deliveries must commit for real or
 * there is no race to observe, and the recorder's {@code REQUIRES_NEW}
 * rows would outlive a test rollback anyway — every fixture is owned
 * via unique tags and cleaned up explicitly, the
 * {@code OrderIdempotencyTest} pattern.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentWebhookConcurrencyTest {

  private static final String SECRET = WebhookSecretStartupCheck.DEV_PLACEHOLDER_SECRET;
  private static final String URL = "/api/payments/webhooks/mock-psp";
  private static final int ROUNDS = 3;

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private WebhookEventRepository events;
  @Autowired private PasswordService passwords;
  @Autowired private WebhookProperties properties;

  private User seller;
  private User buyer;

  @BeforeEach
  void seedUsers() {
    assertThat(properties.getWebhookSecret()).isEqualTo(SECRET);
    // Committed rows from other webhook test classes (the recorder's
    // REQUIRES_NEW commits outlive their fixtures too) must not leak
    // into this class's ledger counts.
    events.deleteAll();
    String tag = "wh-race-" + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
  }

  @AfterEach
  void cleanUp() {
    events.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private Order seedPaidOrder(String captureId) {
    Item item = new Item("Webhook race item " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(OrderStatus.PAID);
    order.setCaptureId(captureId);
    return orders.save(order);
  }

  /** One delivery's raw outcome: HTTP status + body, or the throw. */
  private record Delivery(int status, String body) {}

  private Delivery deliver(CountDownLatch start, String body, String sig) {
    try {
      start.await();
      MvcResult result = mockMvc.perform(post(URL)
              .contentType(MediaType.APPLICATION_JSON)
              .header(PaymentWebhookService.SIGNATURE_HEADER, sig)
              .content(body))
          .andReturn();
      return new Delivery(result.getResponse().getStatus(),
          result.getResponse().getContentAsString());
    } catch (Exception e) {
      return new Delivery(-1, String.valueOf(e));
    }
  }

  @Test
  void concurrentSameEventDeliveries_exactlyOneApplies_loserDedupedNever5xx() throws Exception {
    Order order = seedPaidOrder("cap_mock_wh_race");

    for (int round = 0; round < ROUNDS; round++) {
      String eventId = "evt_race_" + round + "_" + UUID.randomUUID().toString().substring(0, 8);
      String body = "{\"id\":\"" + eventId + "\",\"type\":\"capture.succeeded\","
          + "\"captureId\":\"cap_mock_wh_race\"}";
      String sig = PaymentWebhookService.sign(SECRET,
          java.time.Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8));

      CountDownLatch start = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        List<Future<Delivery>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          futures.add(pool.submit(() -> deliver(start, body, sig)));
        }
        start.countDown();

        List<Delivery> deliveries = new ArrayList<>();
        for (Future<Delivery> f : futures) {
          try {
            deliveries.add(f.get(60, TimeUnit.SECONDS));
          } catch (ExecutionException e) {
            throw new AssertionError("race thread died unexpectedly", e.getCause());
          }
        }

        // Both deliveries answered 200 — never a 5xx, never a leaked
        // constraint error or rollback-only failure in the envelope.
        assertThat(deliveries)
            .as("round %d deliveries", round)
            .allSatisfy(d -> {
              assertThat(d.status()).isEqualTo(200);
              assertThat(d.body()).contains("\"eventId\":\"" + eventId + "\"");
              assertThat(d.body()).contains("\"outcome\":\"MATCHED\"");
              assertThat(d.body()).doesNotContain("internal error");
            });
        // Exactly one applied; the other is the deduped replay.
        assertThat(deliveries.stream().filter(d -> d.body().contains("\"duplicate\":false")))
            .as("round %d winners", round)
            .hasSize(1);
        assertThat(deliveries.stream().filter(d -> d.body().contains("\"duplicate\":true")))
            .as("round %d deduped losers", round)
            .hasSize(1);
        // Exactly one ledger row for this event id.
        assertThat(events.findByEventId(eventId))
            .as("round %d ledger row", round)
            .hasValueSatisfying(e -> assertThat(e.getOutcome())
                .isEqualTo(WebhookEvent.Outcome.MATCHED));
      } finally {
        pool.shutdownNow();
      }
    }

    assertThat(events.findAll()).hasSize(ROUNDS);
    // The webhook moved no money state by itself, however it raced.
    assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PAID);
  }
}
