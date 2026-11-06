package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDispute;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.Item;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit tests for {@link OrderReceiptService} (backlog #338, PIT
 * eighth pilot). No Spring context: the repositories are Mockito mocks,
 * so every receipt branch is pinned directly and a mutant is killed by
 * an assertion about behaviour, not by a context failing to boot. The
 * existing {@code OrderReceiptTest} drives the same service through
 * MockMvc, but its context-boot cost would make every PIT mutant pay a
 * full boot, so it stays out of PIT's {@code targetTests} on the same
 * terms as the TOTP, filter and payout suites in
 * {@code docs/mutation.md}.
 *
 * <p>Two branches only this suite can pin: the DISPUTED disjunct of the
 * {@code paid} flag (a dispute opened through the API always follows a
 * payment, so the MockMvc suite only ever sees DISPUTED with a capture
 * id set — the disjunct is unreachable there), and the
 * {@code "unknown"} username for a deleted account (the MockMvc suite
 * never deletes a user row mid-test).
 */
class OrderReceiptServiceTest {

  private static final Long BUYER_ID = 11L;
  private static final Long SELLER_ID = 3L;
  private static final Long STRANGER_ID = 99L;

  private OrderRepository orders;
  private UserRepository users;
  private PaymentAttemptRecorder attempts;
  private OrderDisputeRepository disputes;
  private OrderReceiptService service;

  @BeforeEach
  void setUp() {
    orders = mock(OrderRepository.class);
    users = mock(UserRepository.class);
    attempts = mock(PaymentAttemptRecorder.class);
    disputes = mock(OrderDisputeRepository.class);
    service = new OrderReceiptService(orders, users, attempts, disputes);
  }

  private Order order(OrderStatus status) {
    Item item = new Item("Film camera", "Canon AE-1", 88000L, SELLER_ID);
    Order order = new Order(item, BUYER_ID, 88000L);
    order.setStatus(status);
    return order;
  }

  private void stubOrder(Order order) {
    when(orders.findById(any())).thenReturn(Optional.of(order));
    when(users.findById(eq(BUYER_ID)))
        .thenReturn(Optional.of(new User("buyer", "buyer@example.com", "hash")));
    when(users.findById(eq(SELLER_ID)))
        .thenReturn(Optional.of(new User("seller", "seller@example.com", "hash")));
    when(attempts.findLatestRefunded(anyLong()))
        .thenReturn(Optional.empty());
    when(disputes.findByOrderIdAndStatus(any(), eq(DisputeStatus.RESOLVED_REFUND)))
        .thenReturn(Optional.empty());
  }

  @Test
  void unknownOrder_throws404() {
    when(orders.findById(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getReceiptFor(BUYER_ID, false, 7L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
            .isEqualTo(404))
        .hasMessageContaining("order not found");
  }

  @Test
  void stranger_throws404_sameMessageAsUnknownId() {
    stubOrder(order(OrderStatus.PENDING));

    // No existence leak: identical status and message to unknown id.
    assertThatThrownBy(() -> service.getReceiptFor(STRANGER_ID, false, 7L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
            .isEqualTo(404))
        .hasMessageContaining("order not found");
  }

  @Test
  void pendingReceipt_buyerOk_markedUnpaid() {
    stubOrder(order(OrderStatus.PENDING));

    ReceiptDto receipt = service.getReceiptFor(BUYER_ID, false, 7L);

    assertThat(receipt.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(receipt.paid()).isFalse();
    assertThat(receipt.amountCents()).isEqualTo(88000L);
    assertThat(receipt.formattedPrice()).isEqualTo("¥880.00");
    assertThat(receipt.buyerUsername()).isEqualTo("buyer");
    assertThat(receipt.sellerUsername()).isEqualTo("seller");
    assertThat(receipt.refundedAmountCents()).isNull();
    assertThat(receipt.refundReason()).isNull();
    assertThat(receipt.disputeResolutionNote()).isNull();
  }

  @Test
  void receipt_sellerAndAdminCanRead() {
    stubOrder(order(OrderStatus.PENDING));

    assertThat(service.getReceiptFor(SELLER_ID, false, 7L).buyerUsername())
        .isEqualTo("buyer");
    assertThat(service.getReceiptFor(STRANGER_ID, true, 7L).buyerUsername())
        .isEqualTo("buyer");
  }

  @Test
  void pendingOrderWithCaptureId_isPaid() {
    Order pending = order(OrderStatus.PENDING);
    pending.setCaptureId("cap_test");
    stubOrder(pending);

    // The capture id alone proves payment, even before the status flips.
    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).paid()).isTrue();
  }

  @Test
  void paidStatuses_arePaidWithoutCaptureId() {
    for (OrderStatus status : new OrderStatus[]{
        OrderStatus.PAID, OrderStatus.COMPLETED, OrderStatus.REFUNDED}) {
      stubOrder(order(status));

      assertThat(service.getReceiptFor(BUYER_ID, false, 7L).paid())
          .as("status %s", status)
          .isTrue();
    }
  }

  @Test
  void disputedOrderWithoutCapture_isPaid() {
    // Only reachable in this unit suite: through the API a dispute
    // always follows a payment, so the MockMvc suite only ever sees
    // DISPUTED with a capture id already set and cannot pin this
    // disjunct on its own.
    stubOrder(order(OrderStatus.DISPUTED));

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).paid()).isTrue();
  }

  @Test
  void shippedOrderWithoutCapture_isUnpaid() {
    // SHIPPED is deliberately absent from the paid disjunction: the
    // capture id is the payment proof, and a shipped order always
    // carries one in production (ship requires PAID first).
    stubOrder(order(OrderStatus.SHIPPED));

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).paid()).isFalse();
  }

  @Test
  void zeroRefundedTotal_mapsToNull() {
    Order pending = order(OrderStatus.PAID);
    pending.setRefundedAmountCents(0L);
    stubOrder(pending);

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).refundedAmountCents())
        .isNull();
  }

  @Test
  void positiveRefundedTotal_isCarried() {
    Order paid = order(OrderStatus.PAID);
    paid.setRefundedAmountCents(30000L);
    stubOrder(paid);

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).refundedAmountCents())
        .isEqualTo(30000L);
  }

  @Test
  void resolvedRefundDispute_carriesItsNote() {
    OrderDispute dispute = new OrderDispute(7L, BUYER_ID, SELLER_ID, "never arrived",
        OrderStatus.PAID);
    dispute.setStatus(DisputeStatus.RESOLVED_REFUND);
    dispute.setResolutionNote("Parcel never arrived; refunding the buyer.");
    stubOrder(order(OrderStatus.REFUNDED));
    when(disputes.findByOrderIdAndStatus(any(), eq(DisputeStatus.RESOLVED_REFUND)))
        .thenReturn(Optional.of(dispute));

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).disputeResolutionNote())
        .isEqualTo("Parcel never arrived; refunding the buyer.");
  }

  @Test
  void noResolvedRefundDispute_noteIsNull() {
    // A release resolution lives under RESOLVED_RELEASE, so the
    // RESOLVED_REFUND lookup finds nothing and no note surfaces.
    stubOrder(order(OrderStatus.PAID));

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).disputeResolutionNote())
        .isNull();
  }

  @Test
  void latestRefundedAttempt_carriesItsReason() {
    stubOrder(order(OrderStatus.REFUNDED));
    when(attempts.findLatestRefunded(anyLong())).thenReturn(Optional.of(
        new PaymentAttempt(7L, PaymentAttempt.Outcome.REFUNDED, "key-1", "item damaged")));

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).refundReason())
        .isEqualTo("item damaged");
  }

  @Test
  void noRefundedAttempt_reasonIsNull() {
    stubOrder(order(OrderStatus.REFUNDED));

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).refundReason()).isNull();
  }

  @Test
  void missingBuyer_rendersUnknown() {
    stubOrder(order(OrderStatus.PENDING));
    when(users.findById(eq(BUYER_ID))).thenReturn(Optional.empty());

    assertThat(service.getReceiptFor(SELLER_ID, false, 7L).buyerUsername())
        .isEqualTo("unknown");
  }

  @Test
  void missingSeller_rendersUnknown() {
    stubOrder(order(OrderStatus.PENDING));
    when(users.findById(eq(SELLER_ID))).thenReturn(Optional.empty());

    assertThat(service.getReceiptFor(BUYER_ID, false, 7L).sellerUsername())
        .isEqualTo("unknown");
  }
}
