package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Webhook receiver integration tests against a real MySQL 8
 * (Testcontainers). Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test
 * phase — this class states that here instead of being skipped
 * silently on a Docker-less machine.
 *
 * <p>The MockMvc suite ({@code PaymentWebhookTest}) and the H2 race
 * suite ({@code PaymentWebhookConcurrencyTest}) prove the contract
 * on H2. What H2 cannot prove is the part the receiver's correctness
 * leans on: the {@code webhook_event} {@code event_id} UNIQUE
 * constraint (Flyway V28) is the arbiter when two deliveries of one
 * event race, and duplicate-key behaviour under concurrent InnoDB
 * inserts — statement rollback inside a still-viable transaction,
 * lock waits on the unique index — is exactly what differs between
 * H2's MySQL mode and real MySQL 8 (the #47 lesson).
 *
 * <ul>
 *   <li>Concurrent duplicate deliveries (repeated rounds, one latch
 *       per round): exactly one applies — both answer 200, exactly
 *       one ack carries {@code duplicate=false}, the loser answers
 *       the deduped envelope with the stored outcome (never a 5xx,
 *       never a raw constraint error or rollback-only failure
 *       leaking through {@code GlobalExceptionHandler}), and
 *       exactly one {@code webhook_event} row exists per event id.
 *       The pre-#161 receiver failed the insert-race interleaving
 *       of this scenario with a 500 (its duplicate-key catch sat
 *       inside the transaction the repository proxy had marked
 *       rollback-only); the {@link WebhookEventRecorder}
 *       {@code REQUIRES_NEW} split is what this scenario now pins
 *       on InnoDB.</li>
 *   <li>Sequential replay on real MySQL: the second delivery of an
 *       applied event is the stored ack with {@code duplicate=true}
 *       and still one row.</li>
 *   <li>A stale-timestamp delivery (#149 — correctly HMAC-signed
 *       for a timestamp outside the freshness window) is 401 and
 *       writes no row: signature verification runs before anything
 *       is recorded, on the real engine too.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PaymentWebhookMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

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
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    // webhook_event carries no FKs; orders reference items and users.
    events.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private String tag(String role) {
    return role + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private User newUser(String role) {
    String t = tag(role);
    return users.save(new User(t, t + "@example.com",
        passwords.encode("webhook-it-password")));
  }

  private Order seedPaidOrder(User seller, User buyer, String captureId) {
    Item item = new Item("Webhook IT item " + tag("item"), "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(OrderStatus.PAID);
    order.setCaptureId(captureId);
    return orders.save(order);
  }

  private String sign(String body) {
    return PaymentWebhookService.sign(SECRET, Instant.now().getEpochSecond(),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private String signAt(String body, Instant at) {
    return PaymentWebhookService.sign(SECRET, at.getEpochSecond(),
        body.getBytes(StandardCharsets.UTF_8));
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
    assertThat(properties.getWebhookSecret()).isEqualTo(SECRET);
    // The race arbiter the receiver relies on: prove the constraint
    // Flyway V28 created on this MySQL 8 carries the name and shape
    // the code assumes (mirrors OrderIdempotencyMySqlIT's check).
    Integer uqCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS"
            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'webhook_event'"
            + " AND CONSTRAINT_NAME = 'uq_webhook_event_event_id'"
            + " AND CONSTRAINT_TYPE = 'UNIQUE'",
        Integer.class);
    assertThat(uqCount).isEqualTo(1);

    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedPaidOrder(seller, buyer, "cap_mock_wh_it_race");

    for (int round = 0; round < ROUNDS; round++) {
      String eventId = "evt_it_race_" + round + "_"
          + UUID.randomUUID().toString().substring(0, 8);
      String body = "{\"id\":\"" + eventId + "\",\"type\":\"capture.succeeded\","
          + "\"captureId\":\"cap_mock_wh_it_race\"}";
      String sig = sign(body);

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

        // Timing-invariant contract (the class javadoc): both 200,
        // exactly one applied, the loser deduped — never a 5xx and
        // never a leaked constraint/rollback error in the envelope.
        assertThat(deliveries)
            .as("round %d deliveries", round)
            .allSatisfy(d -> {
              assertThat(d.status()).isEqualTo(200);
              assertThat(d.body()).contains("\"eventId\":\"" + eventId + "\"");
              assertThat(d.body()).contains("\"outcome\":\"MATCHED\"");
              assertThat(d.body()).doesNotContain("internal error");
            });
        assertThat(deliveries.stream().filter(d -> d.body().contains("\"duplicate\":false")))
            .as("round %d winners", round)
            .hasSize(1);
        assertThat(deliveries.stream().filter(d -> d.body().contains("\"duplicate\":true")))
            .as("round %d deduped losers", round)
            .hasSize(1);
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

  @Test
  void sequentialReplayOnRealMySql_secondDeliveryIsStoredAck_singleRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    seedPaidOrder(seller, buyer, "cap_mock_wh_it_replay");
    String body = "{\"id\":\"evt_it_replay\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_it_replay\"}";

    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .header(PaymentWebhookService.SIGNATURE_HEADER, sign(body)).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.eventId").value("evt_it_replay"))
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(false));
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .header(PaymentWebhookService.SIGNATURE_HEADER, sign(body)).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(true));

    assertThat(events.findAll()).hasSize(1);
  }

  @Test
  void staleTimestampDelivery_is401AndWritesNoRow() throws Exception {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    seedPaidOrder(seller, buyer, "cap_mock_wh_it_stale");
    String body = "{\"id\":\"evt_it_stale\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_it_stale\"}";

    // Correctly HMAC-signed — for a timestamp 10 minutes old against
    // the default 5-minute tolerance (#149). Freshness rejects it
    // before parsing or recording, so the ledger stays empty.
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
            .header(PaymentWebhookService.SIGNATURE_HEADER,
                signAt(body, Instant.now().minusSeconds(600)))
            .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    assertThat(events.findByEventId("evt_it_stale")).isEmpty();
    assertThat(events.findAll()).isEmpty();
  }
}
