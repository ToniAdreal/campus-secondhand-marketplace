package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toni.marketplace.auth.PasswordService;
import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.common.PaymentDeclinedException;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Payment-attempt ledger integration tests against a real MySQL 8
 * (Testcontainers). Requires Docker; runs in CI via
 * {@code mvn verify -Pintegration}, not in the default unit-test
 * phase — this class states that here instead of being skipped
 * silently on a Docker-less machine.
 *
 * <p>The MockMvc suite ({@code PaymentAttemptTest}) proves the contract
 * on H2. What H2 cannot prove is the part the ledger's correctness
 * leans on: transaction semantics under InnoDB. The DECLINED row is
 * written by {@link PaymentAttemptRecorder} in a {@code REQUIRES_NEW}
 * transaction while the pay transaction that threw
 * {@link PaymentDeclinedException} rolls back — suspension, commit
 * ordering and rollback isolation across two real transactions are
 * exactly what differs between H2's MySQL mode and real MySQL 8 (the
 * #47 lesson, and the same seam {@code PaymentWebhookMySqlIT} pins for
 * the webhook ledger). The SUCCEEDED row is the mirror image: written
 * inside the capture transaction, it must exist exactly when the pay
 * committed — and an idempotent re-pay must add no second row.
 *
 * <p>Calls go through {@code OrderService} directly (no MockMvc): the
 * transaction boundaries under test are the service's, and HTTP
 * mapping is already pinned by the H2 suite.
 */
@Testcontainers
@SpringBootTest
class PaymentAttemptMySqlIT {

  @Container
  @ServiceConnection
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

  @Autowired private OrderService orderService;
  @Autowired private UserRepository users;
  @Autowired private ItemRepository items;
  @Autowired private OrderRepository orders;
  @Autowired private PaymentAttemptRepository attempts;
  @Autowired private PasswordService passwords;

  @AfterEach
  void cleanUp() {
    attempts.deleteAll();
    orders.deleteAll();
    items.deleteAll();
    users.deleteAll();
  }

  private User newUser(String role) {
    String tag = role + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.save(new User(tag, tag + "@example.com",
        passwords.encode("attempt-it-password")));
  }

  private Order seedPendingOrder(User seller, User buyer) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, seller.getId());
    item.setStatus(ItemStatus.RESERVED);
    item = items.save(item);
    return orders.save(new Order(item, buyer.getId(), 88000L));
  }

  @Test
  void declinedAttemptSurvivesPayRollbackOnRealTransactions() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedPendingOrder(seller, buyer);

    assertThatThrownBy(() ->
        orderService.pay(buyer.getId(), order.getId(), PaymentService.DECLINE_TOKEN))
        .isInstanceOf(PaymentDeclinedException.class);

    // The pay transaction rolled back on InnoDB: the order is still
    // PENDING and the capture key it generated was never persisted.
    assertThat(orders.findById(order.getId())).hasValueSatisfying(o -> {
      assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(o.getCaptureIdempotencyKey()).isNull();
    });
    // …but the DECLINED ledger row committed in its own transaction.
    List<PaymentAttempt> rows =
        attempts.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getOutcome()).isEqualTo(PaymentAttempt.Outcome.DECLINED);
    assertThat(rows.get(0).getIdempotencyKey()).startsWith("cap_");
  }

  @Test
  void succeededAttemptCommitsWithThePay_andReplayAddsNoSecondRow() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedPendingOrder(seller, buyer);

    orderService.pay(buyer.getId(), order.getId(), null);

    assertThat(orders.findById(order.getId())).hasValueSatisfying(o ->
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID));
    List<PaymentAttempt> rows =
        attempts.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCEEDED);

    // Idempotent re-pay: returns the PAID order, writes no second row.
    orderService.pay(buyer.getId(), order.getId(), null);
    assertThat(attempts.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId()))
        .hasSize(1);
  }

  @Test
  void declineThenSuccessOnRealTransactions_ledgerHoldsBothAttempts() {
    User seller = newUser("seller");
    User buyer = newUser("buyer");
    Order order = seedPendingOrder(seller, buyer);

    assertThatThrownBy(() ->
        orderService.pay(buyer.getId(), order.getId(), PaymentService.DECLINE_TOKEN))
        .isInstanceOf(PaymentDeclinedException.class);
    orderService.pay(buyer.getId(), order.getId(), null);

    List<PaymentAttempt> rows =
        attempts.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getOutcome()).isEqualTo(PaymentAttempt.Outcome.SUCCEEDED);
    assertThat(rows.get(1).getOutcome()).isEqualTo(PaymentAttempt.Outcome.DECLINED);
    // Distinct keys: the declined attempt's key rolled back with its
    // pay transaction, so the successful retry minted a fresh one.
    assertThat(rows.get(0).getIdempotencyKey()).startsWith("cap_");
    assertThat(rows.get(1).getIdempotencyKey()).startsWith("cap_");
    assertThat(rows.get(0).getIdempotencyKey())
        .isNotEqualTo(rows.get(1).getIdempotencyKey());
  }
}
