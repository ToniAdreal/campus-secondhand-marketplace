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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mock-PSP webhook receiver (backlog #140, timestamped signatures
 * #149): POST /api/payments/webhooks/mock-psp is public but
 * HMAC-authenticated with a Stripe-style header
 * (t=<unix seconds>,v1=<hmac over "<t>.<raw body>">) that must also be
 * fresh (default 5-minute tolerance). A valid capture.succeeded for a PAID order records a MATCHED event row;
 * a tampered body or wrong secret is 401 and records nothing; a replayed
 * event id applies once; an unknown capture id (or an order not yet in
 * the matching state) is a MISMATCH answered 200 — the webhook never
 * moves money state by itself, and the order rows are asserted unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentWebhookTest {

  private static final AtomicLong SEQ = new AtomicLong();
  private static final String SECRET = WebhookSecretStartupCheck.DEV_PLACEHOLDER_SECRET;
  private static final String URL = "/api/payments/webhooks/mock-psp";

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
    String tag = "wh-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
  }

  private Order seedOrder(OrderStatus status, String captureId, String refundId) {
    Item item = new Item("Webhook item " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(status);
    order.setCaptureId(captureId);
    order.setRefundId(refundId);
    return orders.save(order);
  }

  private String sign(String body) {
    return PaymentWebhookService.sign(SECRET, java.time.Instant.now().getEpochSecond(),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private String signAt(String body, java.time.Instant at) {
    return PaymentWebhookService.sign(SECRET, at.getEpochSecond(),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private org.springframework.test.web.servlet.ResultActions deliver(String body, String sig)
      throws Exception {
    var req = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
    if (sig != null) {
      req = req.header(PaymentWebhookService.SIGNATURE_HEADER, sig);
    }
    return mockMvc.perform(req);
  }

  @Test
  void validCaptureWebhookRecordsMatchedEvent() throws Exception {
    seedOrder(OrderStatus.PAID, "cap_mock_wh_paid", null);
    String body = "{\"id\":\"evt_cap_ok\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_paid\"}";
    deliver(body, sign(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.eventId").value("evt_cap_ok"))
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(false));
    assertThat(events.findByEventId("evt_cap_ok"))
        .hasValueSatisfying(e -> assertThat(e.getOutcome())
            .isEqualTo(WebhookEvent.Outcome.MATCHED));
  }

  @Test
  void validRefundWebhookRecordsMatchedEvent() throws Exception {
    seedOrder(OrderStatus.REFUNDED, "cap_mock_wh_ref", "rfd_mock_wh_ref");
    String body = "{\"id\":\"evt_rfd_ok\",\"type\":\"refund.succeeded\","
        + "\"refundId\":\"rfd_mock_wh_ref\"}";
    deliver(body, sign(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"));
    assertThat(events.countByOutcome(WebhookEvent.Outcome.MATCHED)).isEqualTo(1);
  }

  @Test
  void tamperedBodyIs401AndRecordsNothing() throws Exception {
    seedOrder(OrderStatus.PAID, "cap_mock_wh_tamper", null);
    String body = "{\"id\":\"evt_tamper\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_tamper\"}";
    String sigForOriginal = sign(body);
    String tampered = body.replace("cap_mock_wh_tamper", "cap_mock_wh_other");
    deliver(tampered, sigForOriginal)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    assertThat(events.findByEventId("evt_tamper")).isEmpty();
  }

  @Test
  void wrongSecretAndMissingSignatureAre401() throws Exception {
    String body = "{\"id\":\"evt_wrong\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_paid\"}";
    String wrongSig = PaymentWebhookService.sign("some-other-secret",
        java.time.Instant.now().getEpochSecond(), body.getBytes(StandardCharsets.UTF_8));
    deliver(body, wrongSig).andExpect(status().isUnauthorized());
    deliver(body, null).andExpect(status().isUnauthorized());
    assertThat(events.findByEventId("evt_wrong")).isEmpty();
  }

  @Test
  void replayedEventIdAppliesOnce() throws Exception {
    seedOrder(OrderStatus.PAID, "cap_mock_wh_replay", null);
    String body = "{\"id\":\"evt_replay\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_replay\"}";
    deliver(body, sign(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.duplicate").value(false));
    deliver(body, sign(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(true));
    assertThat(events.findAll()).hasSize(1);
  }

  @Test
  void unknownCaptureIdIsMismatchAnswered200AndMovesNoState() throws Exception {
    Order pending = seedOrder(OrderStatus.PENDING, null, null);
    String body = "{\"id\":\"evt_unknown\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_does_not_exist\"}";
    deliver(body, sign(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MISMATCH"));
    assertThat(events.findByEventId("evt_unknown"))
        .hasValueSatisfying(e -> assertThat(e.getOutcome())
            .isEqualTo(WebhookEvent.Outcome.MISMATCH));
    assertThat(orders.findById(pending.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void captureForPendingOrderCarryingNoMatchingStateIsMismatch() throws Exception {
    // An order whose row somehow carries a capture id while still PENDING
    // has not completed the synchronous capture transition — the webhook
    // must not bless it as matched, and must not move it to PAID.
    Order order = seedOrder(OrderStatus.PENDING, "cap_mock_wh_pending", null);
    String body = "{\"id\":\"evt_pending\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_pending\"}";
    deliver(body, sign(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MISMATCH"));
    assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void malformedPayloadWithValidSignatureIs400() throws Exception {
    String body = "{\"type\":\"capture.succeeded\"}";
    deliver(body, sign(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void staleTimestampIs401EvenWithValidHmacForThatTimestamp() throws Exception {
    // Backlog #149: signed 10 minutes ago (tolerance is the default
    // 5 min) with a cryptographically valid HMAC for that timestamp —
    // freshness rejects it, and nothing is recorded.
    seedOrder(OrderStatus.PAID, "cap_mock_wh_stale", null);
    String body = "{\"id\":\"evt_stale\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_stale\"}";
    deliver(body, signAt(body, java.time.Instant.now().minusSeconds(600)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    assertThat(events.findByEventId("evt_stale")).isEmpty();
  }

  @Test
  void tamperedTimestampIs401AndRecordsNothing() throws Exception {
    // Backlog #149: the HMAC covers "<t>.<body>", so reusing a fresh
    // delivery's v1 under a different (still fresh) t cannot verify.
    seedOrder(OrderStatus.PAID, "cap_mock_wh_tstamper", null);
    String body = "{\"id\":\"evt_tstamper\",\"type\":\"capture.succeeded\","
        + "\"captureId\":\"cap_mock_wh_tstamper\"}";
    String fresh = sign(body);
    String digest = fresh.substring(fresh.indexOf(",v1=") + 4);
    String tampered = "t=" + java.time.Instant.now().minusSeconds(30).getEpochSecond()
        + ",v1=" + digest;
    deliver(body, tampered)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
    assertThat(events.findByEventId("evt_tstamper")).isEmpty();
  }
}
