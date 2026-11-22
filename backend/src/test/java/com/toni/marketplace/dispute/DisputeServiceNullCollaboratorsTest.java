package com.toni.marketplace.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.order.Order;
import com.toni.marketplace.order.OrderRepository;
import com.toni.marketplace.order.OrderStatus;
import com.toni.marketplace.order.PaymentService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Backlog #409 — the PIT widening to {@link DisputeService} exposed four
 * defensive branches no driving suite reached: the null fallbacks in the
 * private {@code clock()} / {@code audit()} / {@code notifications()} /
 * {@code metrics()} helpers (the {@code field != null ? field : noop()}
 * false side), and the {@code payouts != null} guard in
 * {@link DisputeService#resolveRefund} (the 6-arg constructor used by
 * direct unit tests leaves {@code payouts} null, but the only pure-unit
 * suite, {@code DisputeRefundClaimOrderTest}, throws at the claim flush
 * before reaching it). Spring always injects non-null collaborators,
 * so these branches exist only for direct construction — they are
 * pinned here by constructing the service with explicit nulls, the way
 * a hand-built unit test would.
 */
@ExtendWith(MockitoExtension.class)
class DisputeServiceNullCollaboratorsTest {

  private static final long ADMIN_ID = 1L;
  private static final long ORDER_ID = 33L;
  private static final long ITEM_ID = 5L;
  private static final long BUYER_ID = 22L;
  private static final long SELLER_ID = 11L;
  private static final long DISPUTE_ID = 7L;

  @Mock
  private OrderDisputeRepository disputes;

  @Mock
  private OrderRepository orders;

  @Mock
  private ItemRepository items;

  @Mock
  private PaymentService payments;

  private static Item reservedListing() {
    Item item = new Item("Race camera", "seeded", 10_000L, SELLER_ID);
    item.setStatus(ItemStatus.RESERVED);
    ReflectionTestUtils.setField(item, "id", ITEM_ID);
    return item;
  }

  private static Order paidOrder(Item item) {
    Order order = new Order(item, BUYER_ID, 10_000L);
    order.setStatus(OrderStatus.PAID);
    ReflectionTestUtils.setField(order, "id", ORDER_ID);
    // openDispute measures the dispute window from paidAt for a PAID
    // order (backlog #418), falling back to updatedAt when the stamp is
    // null — this hand-built row carries no paidAt, so the fallback is
    // what applies, and updatedAt (which @PrePersist only stamps on a
    // persisted row) still needs stamping like a just-paid row.
    ReflectionTestUtils.setField(order, "updatedAt", Instant.now());
    return order;
  }

  private static OrderDispute openDispute() {
    return new OrderDispute(ORDER_ID, BUYER_ID, SELLER_ID, "broken", OrderStatus.PAID);
  }

  @Test
  void openDispute_withNullCollaborators_fallsBackToNoops() {
    Item item = reservedListing();
    Order order = paidOrder(item);
    // The full constructor with nulls: clock falls back to the system
    // clock, metrics/audit/notifications to their noops, payouts to
    // null (skipped, not dereferenced). A mutant that negates any of
    // the four ternaries dereferences null here and fails the test.
    DisputeService service = new DisputeService(disputes, orders, items, payments,
        null, null, null, new DisputeProperties(), null, null);
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(disputes.findByOrderId(ORDER_ID)).thenReturn(List.of());
    when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    when(disputes.save(any(OrderDispute.class))).thenAnswer(i -> i.getArgument(0));

    DisputeDto dto = service.openDispute(BUYER_ID, ORDER_ID, "the lens arrived cracked");

    assertThat(dto.status()).isEqualTo(DisputeStatus.OPEN);
    assertThat(dto.previousStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.DISPUTED);
  }

  @Test
  void resolveRefund_withNullPayouts_skipsAdjustment_noNpe() {
    Item item = reservedListing();
    Order order = paidOrder(item);
    order.setStatus(OrderStatus.DISPUTED);
    // The 6-arg constructor is the direct-unit-test seam: metrics,
    // audit and notifications are noops and payouts is null, so the
    // dispute-refund adjustment is skipped rather than dereferenced.
    DisputeService service = new DisputeService(disputes, orders, items, payments,
        new DisputeProperties(), Clock.systemUTC());
    when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(openDispute()));
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(items.findById(ITEM_ID)).thenReturn(Optional.of(item));
    when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    when(items.save(any(Item.class))).thenAnswer(i -> i.getArgument(0));
    when(disputes.save(any(OrderDispute.class))).thenAnswer(i -> i.getArgument(0));
    when(payments.refund(any(Order.class), anyString(), anyLong()))
        .thenReturn(new PaymentService.RefundResult("rfd_test", 10_000L));

    DisputeDto dto = service.resolveRefund(ADMIN_ID, DISPUTE_ID, "Carrier confirms loss.");

    assertThat(dto.status()).isEqualTo(DisputeStatus.RESOLVED_REFUND);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(order.getRefundId()).isEqualTo("rfd_test");
    assertThat(item.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
    // The order-scoped refund key is derived once (#65 pattern) and
    // the remainder is the full amount: no partial refund happened.
    verify(payments).refund(eq(order), eq("rfd_" + ORDER_ID + "_1"), eq(10_000L));
    verify(payments).forgetRefund(eq("rfd_" + ORDER_ID + "_1"));
  }
}
