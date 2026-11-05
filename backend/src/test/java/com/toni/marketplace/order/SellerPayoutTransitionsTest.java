package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.dispute.DisputeProperties;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDispute;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.notification.NotificationService;
import com.toni.marketplace.notification.NotificationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link SellerPayoutTransitions} (backlog #333, PIT
 * seventh pilot). No Spring context: the repositories are Mockito mocks,
 * so every transition branch is pinned directly and a mutant is killed by
 * an assertion about behaviour, not by a context failing to boot. The
 * existing {@code SellerPayoutTest} drives the same bean through the
 * sweep service, but its context-boot cost would make every PIT mutant
 * pay a full boot, so it stays out of PIT's {@code targetTests} on the
 * same terms as the TOTP and filter suites in {@code docs/mutation.md}.
 */
class SellerPayoutTransitionsTest {

  private static final long PAYOUT_ID = 7L;
  private static final long ORDER_ID = 11L;
  private static final long SELLER_ID = 3L;

  private SellerPayoutRepository payouts;
  private OrderRepository orders;
  private OrderDisputeRepository disputes;
  private UserRepository users;
  private NotificationService notifications;
  private SellerPayoutTransitions transitions;

  @BeforeEach
  void setUp() {
    payouts = mock(SellerPayoutRepository.class);
    orders = mock(OrderRepository.class);
    disputes = mock(OrderDisputeRepository.class);
    users = mock(UserRepository.class);
    notifications = mock(NotificationService.class);
    DisputeProperties disputeProps = new DisputeProperties();
    disputeProps.setWindow(Duration.ofHours(1));
    transitions = new SellerPayoutTransitions(
        payouts, orders, disputes, users, disputeProps,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), notifications);
  }

  private SellerPayout payout(PayoutStatus status) {
    SellerPayout row = new SellerPayout(ORDER_ID, SELLER_ID, 12_000L);
    row.setStatus(status);
    return row;
  }

  private Order completedOrder(Instant completedAt) {
    Order order = new Order(null, 9L, 12_000L);
    order.setStatus(OrderStatus.COMPLETED);
    order.setCompletedAt(completedAt);
    return order;
  }

  private User seller(boolean disabled) {
    User user = new User("seller", "seller@example.com", "hash");
    user.setDisabled(disabled);
    return user;
  }

  /** Happy-path wiring shared by the promote/pay success cases. */
  private void wirePromotable(SellerPayout row, Order order) {
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(false)));
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    // The lookup key is order.getId(), which is null on a directly
    // constructed Order, so the stub matches any id.
    when(disputes.findByOrderIdAndStatus(any(), eq(DisputeStatus.OPEN)))
        .thenReturn(Optional.empty());
  }

  @Test
  void isSellerHeldReturnsFalseWhenPayoutMissing() {
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.empty());
    assertThat(transitions.isSellerHeld(PAYOUT_ID)).isFalse();
  }

  @Test
  void isSellerHeldReturnsTrueWhenSellerRowMissing() {
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(payout(PayoutStatus.PENDING)));
    when(users.findById(SELLER_ID)).thenReturn(Optional.empty());
    assertThat(transitions.isSellerHeld(PAYOUT_ID)).isTrue();
  }

  @Test
  void isSellerHeldReturnsTrueWhenSellerDisabled() {
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(payout(PayoutStatus.PENDING)));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(true)));
    assertThat(transitions.isSellerHeld(PAYOUT_ID)).isTrue();
  }

  @Test
  void isSellerHeldReturnsFalseWhenSellerEnabled() {
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(payout(PayoutStatus.PENDING)));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(false)));
    assertThat(transitions.isSellerHeld(PAYOUT_ID)).isFalse();
  }

  @Test
  void promoteReturnsFalseWhenPayoutMissing() {
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.empty());
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteReturnsFalseWhenStatusNotPending() {
    SellerPayout row = payout(PayoutStatus.PAYABLE);
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH)).isFalse();
    assertThat(row.getStatus()).isEqualTo(PayoutStatus.PAYABLE);
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteReturnsFalseWhenSellerHeld() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(true)));
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteReturnsFalseWhenOrderMissing() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(false)));
    when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteReturnsFalseWhenOrderNotCompleted() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    Order order = completedOrder(Instant.EPOCH);
    order.setStatus(OrderStatus.PAID);
    wirePromotable(row, order);
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteReturnsFalseWhenOpenDisputeExists() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    wirePromotable(row, completedOrder(Instant.EPOCH));
    when(disputes.findByOrderIdAndStatus(any(), eq(DisputeStatus.OPEN)))
        .thenReturn(Optional.of(mock(OrderDispute.class)));
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH.plus(Duration.ofHours(2))))
        .isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteReturnsFalseWhenAnchorIsNull() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    Order order = mock(Order.class);
    when(order.getStatus()).thenReturn(OrderStatus.COMPLETED);
    when(order.getCompletedAt()).thenReturn(null);
    when(order.getUpdatedAt()).thenReturn(null);
    wirePromotable(row, order);
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteReturnsFalseWhileWindowStillOpen() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    wirePromotable(row, completedOrder(Instant.EPOCH));
    assertThat(transitions.promoteToPayable(PAYOUT_ID, Instant.EPOCH.plus(Duration.ofMinutes(30))))
        .isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void promoteSucceedsExactlyWhenWindowElapses() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    wirePromotable(row, completedOrder(Instant.EPOCH));
    Instant now = Instant.EPOCH.plus(Duration.ofHours(1));
    assertThat(transitions.promoteToPayable(PAYOUT_ID, now)).isTrue();
    assertThat(row.getStatus()).isEqualTo(PayoutStatus.PAYABLE);
    assertThat(row.getPayableAt()).isEqualTo(now);
    verify(payouts).save(row);
  }

  @Test
  void promoteFallsBackToUpdatedAtWhenCompletedAtMissing() {
    // updatedAt is set only by @PrePersist, so a directly constructed
    // Order has a null updatedAt; the mock pins the fallback anchor exactly.
    SellerPayout row = payout(PayoutStatus.PENDING);
    Order order = mock(Order.class);
    when(order.getStatus()).thenReturn(OrderStatus.COMPLETED);
    when(order.getCompletedAt()).thenReturn(null);
    when(order.getUpdatedAt()).thenReturn(Instant.EPOCH);
    wirePromotable(row, order);
    Instant now = Instant.EPOCH.plus(Duration.ofHours(1)).plusSeconds(1);
    assertThat(transitions.promoteToPayable(PAYOUT_ID, now)).isTrue();
    assertThat(row.getStatus()).isEqualTo(PayoutStatus.PAYABLE);
  }

  @Test
  void markPaidReturnsFalseWhenPayoutMissing() {
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.empty());
    assertThat(transitions.markPaid(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(notifications, never()).record(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void markPaidReturnsFalseWhenStatusNotPayable() {
    SellerPayout row = payout(PayoutStatus.PENDING);
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    assertThat(transitions.markPaid(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
    verify(notifications, never()).record(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void markPaidReturnsFalseWhenSellerHeld() {
    SellerPayout row = payout(PayoutStatus.PAYABLE);
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(true)));
    assertThat(transitions.markPaid(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void markPaidReturnsFalseWhenOrderMissingOrNotCompleted() {
    SellerPayout row = payout(PayoutStatus.PAYABLE);
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(false)));
    when(orders.findById(ORDER_ID)).thenReturn(Optional.empty());
    assertThat(transitions.markPaid(PAYOUT_ID, Instant.EPOCH)).isFalse();
    Order moved = completedOrder(Instant.EPOCH);
    moved.setStatus(OrderStatus.REFUNDED);
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(moved));
    assertThat(transitions.markPaid(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(payouts, never()).save(org.mockito.ArgumentMatchers.any());
    verify(notifications, never()).record(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void markPaidReturnsFalseWhenOpenDisputeExists() {
    SellerPayout row = payout(PayoutStatus.PAYABLE);
    wirePromotable(row, completedOrder(Instant.EPOCH));
    when(disputes.findByOrderIdAndStatus(any(), eq(DisputeStatus.OPEN)))
        .thenReturn(Optional.of(mock(OrderDispute.class)));
    assertThat(transitions.markPaid(PAYOUT_ID, Instant.EPOCH)).isFalse();
    verify(notifications, never()).record(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void markPaidSucceedsWhenNotificationServiceIsNull() {
    // The null guard in notifications() is a real branch: an explicit
    // null must degrade to the noop service, not throw inside the
    // transition that already decided to pay.
    SellerPayout row = payout(PayoutStatus.PAYABLE);
    DisputeProperties disputeProps = new DisputeProperties();
    disputeProps.setWindow(Duration.ofHours(1));
    SellerPayoutTransitions noNotify = new SellerPayoutTransitions(
        payouts, orders, disputes, users, disputeProps,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null);
    when(payouts.findById(PAYOUT_ID)).thenReturn(Optional.of(row));
    when(users.findById(SELLER_ID)).thenReturn(Optional.of(seller(false)));
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder(Instant.EPOCH)));
    when(disputes.findByOrderIdAndStatus(any(), eq(DisputeStatus.OPEN)))
        .thenReturn(Optional.empty());
    assertThat(noNotify.markPaid(PAYOUT_ID, Instant.EPOCH)).isTrue();
    assertThat(row.getStatus()).isEqualTo(PayoutStatus.PAID);
    verify(payouts).save(row);
  }

  @Test
  void markPaidSucceedsAndNotifiesExactlyOnce() {
    SellerPayout row = payout(PayoutStatus.PAYABLE);
    wirePromotable(row, completedOrder(Instant.EPOCH));
    Instant now = Instant.EPOCH.plus(Duration.ofHours(2));
    assertThat(transitions.markPaid(PAYOUT_ID, now)).isTrue();
    assertThat(row.getStatus()).isEqualTo(PayoutStatus.PAID);
    assertThat(row.getPaidAt()).isEqualTo(now);
    verify(payouts).save(row);
    verify(notifications).record(SELLER_ID, NotificationType.PAYOUT_PAID, ORDER_ID);
    // A rerun returns false before reaching the record, so it notifies nothing twice.
    assertThat(transitions.markPaid(PAYOUT_ID, now)).isFalse();
    verify(notifications, org.mockito.Mockito.times(1)).record(
        SELLER_ID, NotificationType.PAYOUT_PAID, ORDER_ID);
  }
}
