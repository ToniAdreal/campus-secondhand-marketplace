package com.toni.marketplace.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backlog #386 — {@link DisputeService#resolveRefund} must claim the
 * dispute (order + dispute rows flushed) <em>before</em> calling the
 * mock PSP refund seam. The old order called the PSP first and flushed
 * after: a racing resolution that lost at the flush had already
 * incremented the mock's {@code refundsIssued} counter — a
 * non-transactional side effect no rollback can undo — so the
 * {@code OrderDisputeRaceMySqlIT} saw phantom refunds for release wins.
 * Pure unit test, mirroring {@code OrderLifecycleMetricsTest}: the
 * service is constructed by hand with mocked repositories, and the
 * claim flush is stubbed to fail, so the test pins the ordering
 * without a database or threads.
 */
@ExtendWith(MockitoExtension.class)
class DisputeRefundClaimOrderTest {

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

  private DisputeService service;

  @BeforeEach
  void setUp() {
    service = new DisputeService(disputes, orders, items, payments,
        new DisputeProperties(), Clock.systemUTC());
  }

  private static Item reservedListing() {
    Item item = new Item("Race camera", "seeded", 10_000L, SELLER_ID);
    item.setStatus(ItemStatus.RESERVED);
    ReflectionTestUtils.setField(item, "id", ITEM_ID);
    return item;
  }

  private static Order disputedOrder(Item item) {
    Order order = new Order(item, BUYER_ID, 10_000L);
    order.setStatus(OrderStatus.DISPUTED);
    ReflectionTestUtils.setField(order, "id", ORDER_ID);
    return order;
  }

  private static OrderDispute openDispute() {
    return new OrderDispute(ORDER_ID, BUYER_ID, SELLER_ID, "broken", OrderStatus.PAID);
  }

  @Test
  void lostClaimRace_neverCallsPspRefund() {
    Item item = reservedListing();
    Order order = disputedOrder(item);
    when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(openDispute()));
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(items.findById(ITEM_ID)).thenReturn(Optional.of(item));
    when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    when(items.save(any(Item.class))).thenAnswer(i -> i.getArgument(0));
    // The PSP seam itself succeeds: the old order reaches it before the
    // claim flush, so the mock's refund is what the test must catch.
    // Lenient on purpose: with the fix the seam is never reached, so
    // strict stubs would flag this as unnecessary — which is exactly the
    // behaviour under test. Kept (not deleted) so the test still fails on
    // the old order, where the PSP is called before the flush.
    lenient().when(payments.refund(any(Order.class), anyString(), anyLong()))
        .thenReturn(new PaymentService.RefundResult("rfd_test", 10_000L));
    // The racing resolution won: the claim flush fails with the version
    // conflict before any money moves.
    doThrow(new ObjectOptimisticLockingFailureException(Order.class, ORDER_ID))
        .when(orders).flush();

    assertThatThrownBy(() -> service.resolveRefund(ADMIN_ID, DISPUTE_ID, "Race test refund."))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));

    verify(payments, never()).refund(any(Order.class), anyString(), anyLong());
  }
}
