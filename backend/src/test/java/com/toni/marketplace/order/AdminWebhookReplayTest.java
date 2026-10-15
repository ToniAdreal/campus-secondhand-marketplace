package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.auth.JwtTokenService;
import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.Role;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADMIN webhook-event replay (backlog #170): POST
 * /api/admin/payments/webhooks/{id}/replay re-runs one recorded event
 * through the live receiver's own seams. An already-applied (MATCHED)
 * event is a recorded no-op returning the stored outcome; a MISMATCH
 * event whose order has since reached the matching state applies
 * exactly once (the second replay is the no-op); an event whose
 * recorded timestamp sits outside the #149 freshness window still
 * answers 401 and never applies — replay is not a signature amnesty.
 * Every attempt — no-op, applied and refused alike — bumps the row's
 * replayCount / lastReplayAt and writes a WEBHOOK_EVENT_REPLAYED
 * audit row.
 *
 * <p>Deliberately NOT {@code @Transactional}, like
 * {@code PaymentWebhookTest}: the replay bookkeeping runs through
 * {@code WebhookEventRecorder}'s REQUIRES_NEW transactions, which
 * cannot see uncommitted fixtures — seeds commit for real and are
 * cleaned explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminWebhookReplayTest {

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private WebhookEventRepository events;
  @Autowired private AuditLogRepository auditLog;
  @Autowired private PasswordService passwords;
  @Autowired private JwtTokenService jwt;
  @Autowired private JdbcTemplate jdbc;

  private User seller;
  private User buyer;
  private String adminToken;
  private String userToken;
  private String tag;

  @BeforeEach
  void seedUsers() {
    cleanAll();
    tag = "whr-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    User admin = new User(tag + "-admin", tag + "-admin@example.com",
        passwords.encode("admin-password"));
    admin.setRoles(EnumSet.of(Role.ADMIN));
    admin = users.save(admin);
    seller = users.save(new User(tag + "-seller", tag + "-seller@example.com",
        passwords.encode("seller-password")));
    buyer = users.save(new User(tag + "-buyer", tag + "-buyer@example.com",
        passwords.encode("buyer-password")));
    adminToken = jwt.createTokenPair(admin).accessToken();
    userToken = jwt.createTokenPair(buyer).accessToken();
  }

  @AfterEach
  void cleanUp() {
    cleanAll();
  }

  /** FK-safe order: audit/webhook rows carry no FKs; orders precede items/users. */
  private void cleanAll() {
    auditLog.deleteAll();
    events.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private Order seedOrder(OrderStatus status, String captureId) {
    Item item = new Item("Replay item " + UUID.randomUUID().toString().substring(0, 8),
        "seeded", 5000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    item = items.save(item);
    Order order = new Order(item, buyer.getId(), 5000L);
    order.setStatus(status);
    order.setCaptureId(captureId);
    return orders.save(order);
  }

  private WebhookEvent seedEvent(String suffix, WebhookEvent.Outcome outcome, Instant createdAt) {
    WebhookEvent row = events.saveAndFlush(new WebhookEvent(
        tag + "-" + suffix, PaymentWebhookService.TYPE_CAPTURE_SUCCEEDED,
        "cap_" + tag + "_" + suffix, outcome));
    jdbc.update("update webhook_event set created_at = ? where id = ?",
        Timestamp.from(createdAt), row.getId());
    return row;
  }

  private String captureRef(String suffix) {
    return "cap_" + tag + "_" + suffix;
  }

  @Test
  void replayOfMatchedEventIsRecordedNoOpReturningStoredOutcome() throws Exception {
    Order order = seedOrder(OrderStatus.PAID, captureRef("ok"));
    WebhookEvent row = seedEvent("ok", WebhookEvent.Outcome.MATCHED, Instant.now());

    mockMvc.perform(post("/api/admin/payments/webhooks/" + row.getId() + "/replay")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.eventId").value(row.getEventId()))
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(true))
        .andExpect(jsonPath("$.data.applied").value(false))
        .andExpect(jsonPath("$.data.replayCount").value(1))
        .andExpect(jsonPath("$.data.lastReplayAt").exists());

    // No-op means exactly that: one ledger row, order untouched.
    assertThat(events.count()).isEqualTo(1);
    assertThat(orders.findById(order.getId()))
        .hasValueSatisfying(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    WebhookEvent stored = events.findById(row.getId()).orElseThrow();
    assertThat(stored.getReplayCount()).isEqualTo(1);
    assertThat(stored.getLastReplayAt()).isNotNull();
    assertThat(auditLog.countByAction(AuditAction.WEBHOOK_EVENT_REPLAYED)).isEqualTo(1);

    // The viewer (#156) surfaces the replay bookkeeping (#170).
    mockMvc.perform(get("/api/admin/payments/webhooks")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].replayCount").value(1))
        .andExpect(jsonPath("$.data.content[0].lastReplayAt").exists());
  }

  @Test
  void replayOfMismatchAppliesExactlyOnceAfterOrderReachesMatchingState() throws Exception {
    // The delivery arrived while the order was still PENDING (hence the
    // MISMATCH); the synchronous path has since paid it.
    Order order = seedOrder(OrderStatus.PAID, captureRef("late"));
    WebhookEvent row = seedEvent("late", WebhookEvent.Outcome.MISMATCH, Instant.now());

    mockMvc.perform(post("/api/admin/payments/webhooks/" + row.getId() + "/replay")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(false))
        .andExpect(jsonPath("$.data.applied").value(true))
        .andExpect(jsonPath("$.data.replayCount").value(1));
    assertThat(events.findById(row.getId()))
        .hasValueSatisfying(e -> assertThat(e.getOutcome())
            .isEqualTo(WebhookEvent.Outcome.MATCHED));
    // A webhook never moves money state — the order is as seeded.
    assertThat(orders.findById(order.getId()))
        .hasValueSatisfying(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));

    // Second replay: the stored verdict makes it the deduped no-op.
    mockMvc.perform(post("/api/admin/payments/webhooks/" + row.getId() + "/replay")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MATCHED"))
        .andExpect(jsonPath("$.data.duplicate").value(true))
        .andExpect(jsonPath("$.data.applied").value(false))
        .andExpect(jsonPath("$.data.replayCount").value(2));
    assertThat(events.count()).isEqualTo(1);
    assertThat(auditLog.countByAction(AuditAction.WEBHOOK_EVENT_REPLAYED)).isEqualTo(2);
  }

  @Test
  void replayOfStillMismatchedEventAnswersStoredOutcomeWithoutApplying() throws Exception {
    seedOrder(OrderStatus.PENDING, captureRef("early"));
    WebhookEvent row = seedEvent("early", WebhookEvent.Outcome.MISMATCH, Instant.now());

    mockMvc.perform(post("/api/admin/payments/webhooks/" + row.getId() + "/replay")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.outcome").value("MISMATCH"))
        .andExpect(jsonPath("$.data.duplicate").value(false))
        .andExpect(jsonPath("$.data.applied").value(false));
    assertThat(events.findById(row.getId()))
        .hasValueSatisfying(e -> assertThat(e.getOutcome())
            .isEqualTo(WebhookEvent.Outcome.MISMATCH));
  }

  @Test
  void staleEventReplayStillAnswers401AndNeverApplies() throws Exception {
    // The order would reconcile NOW — but the recorded delivery is an
    // hour old, outside the 5-minute #149 freshness window, and replay
    // does not bypass that check.
    seedOrder(OrderStatus.PAID, captureRef("stale"));
    WebhookEvent row = seedEvent("stale", WebhookEvent.Outcome.MISMATCH,
        Instant.now().minus(Duration.ofHours(1)));

    mockMvc.perform(post("/api/admin/payments/webhooks/" + row.getId() + "/replay")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    // Never applied — but the attempt itself is still counted + audited.
    WebhookEvent stored = events.findById(row.getId()).orElseThrow();
    assertThat(stored.getOutcome()).isEqualTo(WebhookEvent.Outcome.MISMATCH);
    assertThat(stored.getReplayCount()).isEqualTo(1);
    assertThat(auditLog.countByAction(AuditAction.WEBHOOK_EVENT_REPLAYED)).isEqualTo(1);
  }

  @Test
  void unknownEventIdIs404Envelope() throws Exception {
    mockMvc.perform(post("/api/admin/payments/webhooks/999999999/replay")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    assertThat(auditLog.countByAction(AuditAction.WEBHOOK_EVENT_REPLAYED)).isZero();
  }

  @Test
  void nonAdminGets403Envelope() throws Exception {
    WebhookEvent row = seedEvent("authz", WebhookEvent.Outcome.MATCHED, Instant.now());
    mockMvc.perform(post("/api/admin/payments/webhooks/" + row.getId() + "/replay")
            .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  @Test
  void anonymousGets401Envelope() throws Exception {
    WebhookEvent row = seedEvent("anon", WebhookEvent.Outcome.MATCHED, Instant.now());
    mockMvc.perform(post("/api/admin/payments/webhooks/" + row.getId() + "/replay"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }
}
