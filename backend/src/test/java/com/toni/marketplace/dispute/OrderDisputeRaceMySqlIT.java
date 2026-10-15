package com.toni.marketplace.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import com.toni.marketplace.audit.AuditLogRepository;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationRepository;
import com.toni.marketplace.order.IdempotencyKeyRepository;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderService;
import com.toni.marketplace.order.OrderStatus;
import com.toni.marketplace.order.PaymentService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dispute-resolution race integration tests against a real MySQL 8
 * (Testcontainers). Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test phase.
 *
 * <p>Backlog #169: {@code OrderDisputeTest} proves the dispute contract
 * on H2/MockMvc in-process, but the two properties that arbitrate a
 * real race live in the database — the {@code @Version} fail-fast on
 * both the dispute and order rows, and the V37-widened
 * {@code uq_order_active_item} guard that keeps a DISPUTED order
 * "active" on real MySQL (whose generated-column / NULL-in-unique
 * semantics are exactly what differs from H2's MySQL mode — the #47 /
 * #161 lesson). This class races the two ADMIN resolutions against
 * each other on one open dispute: exactly one applies — a refund win
 * means exactly one mock PSP refund and a REFUNDED order, a release
 * win means no refund and the order back at PAID — and the loser
 * answers a client error (422 once it reads the winner's committed
 * state, 409 when its version check fails at flush), never a 5xx and
 * never a second transition.
 */
@Testcontainers
@SpringBootTest
class OrderDisputeRaceMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  /** Rounds per race: enough for both winner orders to be plausible. */
  private static final int ROUNDS = 3;

  private static final AtomicLong SEQ = new AtomicLong();

  @Autowired
  private DisputeService disputeService;

  @Autowired
  private OrderService orderService;

  @Autowired
  private OrderDisputeRepository disputes;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private IdempotencyKeyRepository keys;

  @Autowired
  private ItemRepository items;

  @Autowired
  private UserRepository users;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private AuditLogRepository auditLog;

  @Autowired
  private PaymentService payments;

  @Autowired
  private JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    disputes.deleteAll();
    notifications.deleteAll();
    auditLog.deleteAll();
    keys.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser() {
    String tag = "dispute-it-" + SEQ.incrementAndGet() + "-"
        + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com", "x"));
  }

  /** A PAID order on a RESERVED listing, created and paid through the real services. */
  private long paidOrderId(User buyer, Item item) {
    var created = orderService.createOrder(buyer.getId(), item.getId(),
        "dispute-it-" + UUID.randomUUID());
    orderService.pay(buyer.getId(), created.id());
    return created.id();
  }

  private Item newItem(User seller) {
    Item item = new Item("Race camera", "seeded", 10_000L, seller.getId());
    item.setStatus(ItemStatus.AVAILABLE);
    return items.save(item);
  }

  @Test
  void resolveRace_exactlyOneResolutionApplies() throws Exception {
    User admin = newUser();
    int refundWins = 0;
    long refundsBefore = payments.refundsIssued();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        User seller = newUser();
        User buyer = newUser();
        Item item = newItem(seller);
        long orderId = paidOrderId(buyer, item);
        DisputeDto dispute = disputeService.openDispute(buyer.getId(), orderId, "race");

        // The V37 guard keeps the disputed order active on real MySQL:
        // its generated active_item_id still names the listing, so no
        // second active order can be created for it mid-dispute.
        Long activeItemId = jdbc.queryForObject(
            "SELECT active_item_id FROM orders WHERE id = ?", Long.class, orderId);
        assertThat(activeItemId).isEqualTo(item.getId());

        CountDownLatch start = new CountDownLatch(1);
        Future<Object> refund = pool.submit(() -> {
          start.await();
          try {
            return disputeService.resolveRefund(admin.getId(), dispute.id());
          } catch (ResponseStatusException e) {
            return e;
          }
        });
        Future<Object> release = pool.submit(() -> {
          start.await();
          try {
            return disputeService.resolveRelease(admin.getId(), dispute.id());
          } catch (ResponseStatusException e) {
            return e;
          }
        });
        start.countDown();
        Object refundOutcome = refund.get(60, TimeUnit.SECONDS);
        Object releaseOutcome = release.get(60, TimeUnit.SECONDS);

        boolean refundWon = refundOutcome instanceof DisputeDto;
        boolean releaseWon = releaseOutcome instanceof DisputeDto;
        assertThat(refundWon ^ releaseWon)
            .as("exactly one resolution applies (round %d)", round).isTrue();
        Object loser = refundWon ? releaseOutcome : refundOutcome;
        assertThat(loser).isInstanceOf(ResponseStatusException.class);
        int loserStatus = ((ResponseStatusException) loser).getStatusCode().value();
        assertThat(loserStatus).isIn(409, 422);

        OrderDispute row = disputes.findById(dispute.id()).orElseThrow();
        OrderStatus orderStatus = orders.findById(orderId).orElseThrow().getStatus();
        if (refundWon) {
          refundWins++;
          assertThat(row.getStatus()).isEqualTo(DisputeStatus.RESOLVED_REFUND);
          assertThat(orderStatus).isEqualTo(OrderStatus.REFUNDED);
          assertThat(row.getListingRestored()).isTrue();
        } else {
          assertThat(row.getStatus()).isEqualTo(DisputeStatus.RESOLVED_RELEASE);
          assertThat(orderStatus).isEqualTo(OrderStatus.PAID);
        }
      }
    } finally {
      pool.shutdownNow();
    }
    // Exactly one mock PSP refund per refund win — never two, never one
    // for a release win (the seam's single-invocation guarantee).
    assertThat(payments.refundsIssued() - refundsBefore).isEqualTo(refundWins);
  }

  @Test
  void completedDispute_refundResolutionOnRealMySql_keepsListingSold() {
    User admin = newUser();
    User seller = newUser();
    User buyer = newUser();
    Item item = newItem(seller);
    long orderId = paidOrderId(buyer, item);
    orderService.complete(seller.getId(), false, orderId);
    DisputeDto dispute = disputeService.openDispute(buyer.getId(), orderId, "broken");

    DisputeDto resolved = disputeService.resolveRefund(admin.getId(), dispute.id());

    assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED_REFUND);
    assertThat(resolved.listingRestored()).isFalse();
    assertThat(orders.findById(orderId).orElseThrow().getStatus())
        .isEqualTo(OrderStatus.REFUNDED);
    assertThat(items.findById(item.getId()).orElseThrow().getStatus())
        .isEqualTo(ItemStatus.SOLD);
  }
}
