package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.dispute.DisputeDto;
import com.toni.marketplace.dispute.DisputeProperties;
import com.toni.marketplace.dispute.DisputeService;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDispute;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backlog #348 — the three lifecycle counters {@link OrderMetrics} gained
 * after #93: {@code orders.shipped}, {@code orders.disputed} and
 * {@code orders.handover_confirmed}. Pure unit tests, mirroring
 * {@code SellerPayoutSweepMetricsTest} (#341): the services are
 * constructed by hand with mocked repositories, the counters live on a
 * throwaway {@link SimpleMeterRegistry}, and every assertion reads the
 * counter itself — so a counter that forgets to move, or moves on the
 * wrong transition, fails here. No Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class OrderLifecycleMetricsTest {

  private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
  private static final long SELLER_ID = 11L;
  private static final long BUYER_ID = 22L;
  private static final long ORDER_ID = 33L;
  private static final long ITEM_ID = 5L;
  private static final long DISPUTE_ID = 7L;

  @Mock
  private OrderRepository orders;

  @Mock
  private ItemRepository items;

  @Mock
  private OrderDisputeRepository disputes;

  @Mock
  private PaymentService payments;

  private MeterRegistry registry;
  private OrderService orderService;
  private DisputeService disputeService;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    OrderMetrics metrics = new OrderMetrics(registry);
    orderService = new OrderService(orders, items, mock(IdempotencyKeyService.class),
        payments, mock(OrderCreationService.class), metrics, AuditService.noop(),
        Clock.fixed(NOW, ZoneOffset.UTC));
    disputeService = new DisputeService(disputes, orders, items, payments, metrics,
        AuditService.noop(), NotificationService.noop(), new DisputeProperties(),
        Clock.fixed(NOW, ZoneOffset.UTC), null);
  }

  private double count(String name) {
    return registry.get(name).counter().count();
  }

  private static String sha256Hex(String input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Item listing() {
    Item item = new Item("Desk lamp", "a used desk lamp", 2500L, SELLER_ID);
    item.setStatus(ItemStatus.RESERVED);
    ReflectionTestUtils.setField(item, "id", ITEM_ID);
    return item;
  }

  private static Order paidOrder() {
    Order order = new Order(listing(), BUYER_ID, 2500L);
    order.setStatus(OrderStatus.PAID);
    ReflectionTestUtils.setField(order, "id", ORDER_ID);
    return order;
  }

  private void stubOrderSave() {
    when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
  }

  private void stubItemSave() {
    when(items.save(any(Item.class))).thenAnswer(i -> i.getArgument(0));
  }

  private void stubDisputeSave() {
    when(disputes.save(any(OrderDispute.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void shippedCounter_shipMovesOnlyShipped_reShipMovesNothing() {
    Order order = paidOrder();
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    stubOrderSave();

    OrderDto dto = orderService.ship(SELLER_ID, false, ORDER_ID, "SF Express", "SF123456");

    assertThat(dto.status()).isEqualTo(OrderStatus.SHIPPED);
    assertThat(count("orders.shipped")).isEqualTo(1.0);
    assertThat(count("orders.disputed")).isZero();
    assertThat(count("orders.handover_confirmed")).isZero();
    assertThat(count("orders.completed")).isZero();
    assertThat(count("orders.paid")).isZero();

    // Re-shipping the now-SHIPPED order is a 422 — no second count.
    assertThatThrownBy(() -> orderService.ship(SELLER_ID, false, ORDER_ID, "SF Express", "SF999"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(count("orders.shipped")).isEqualTo(1.0);
  }

  @Test
  void disputedCounter_openMovesOnlyDisputed_reopenMovesNothing() {
    Order order = paidOrder();
    ReflectionTestUtils.setField(order, "updatedAt", NOW);
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(disputes.findByOrderId(ORDER_ID)).thenReturn(List.of());
    stubOrderSave();
    stubDisputeSave();

    DisputeDto dto = disputeService.openDispute(BUYER_ID, ORDER_ID, "arrived broken");

    assertThat(dto.status()).isEqualTo(DisputeStatus.OPEN);
    assertThat(count("orders.disputed")).isEqualTo(1.0);
    assertThat(count("orders.shipped")).isZero();
    assertThat(count("orders.handover_confirmed")).isZero();
    assertThat(count("orders.refunded")).isZero();

    // Idempotent re-open returns the live dispute — no second count.
    OrderDispute live = new OrderDispute(ORDER_ID, BUYER_ID, SELLER_ID,
        "arrived broken", OrderStatus.PAID);
    when(disputes.findByOrderIdAndStatus(ORDER_ID, DisputeStatus.OPEN))
        .thenReturn(Optional.of(live));
    DisputeDto again = disputeService.openDispute(BUYER_ID, ORDER_ID, "arrived broken");
    assertThat(again.status()).isEqualTo(DisputeStatus.OPEN);
    assertThat(count("orders.disputed")).isEqualTo(1.0);
  }

  @Test
  void disputedCounter_resolveRefundMovesRefundedNotDisputed() {
    Order order = paidOrder();
    ReflectionTestUtils.setField(order, "updatedAt", NOW);
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(disputes.findByOrderId(ORDER_ID)).thenReturn(List.of());
    when(items.findById(ITEM_ID)).thenReturn(Optional.of(order.getItem()));
    stubOrderSave();
    stubItemSave();
    stubDisputeSave();

    disputeService.openDispute(BUYER_ID, ORDER_ID, "arrived broken");
    assertThat(count("orders.disputed")).isEqualTo(1.0);

    OrderDispute dispute = new OrderDispute(ORDER_ID, BUYER_ID, SELLER_ID,
        "arrived broken", OrderStatus.PAID);
    ReflectionTestUtils.setField(dispute, "id", DISPUTE_ID);
    when(disputes.findById(DISPUTE_ID)).thenReturn(Optional.of(dispute));
    when(payments.refund(any(Order.class), anyString(), anyLong()))
        .thenReturn(new PaymentService.RefundResult("rfd_mock_1", 2500L));

    DisputeDto resolved = disputeService.resolveRefund(99L, DISPUTE_ID, "item arrived broken");

    assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED_REFUND);
    assertThat(count("orders.refunded")).isEqualTo(1.0);
    assertThat(count("orders.disputed")).isEqualTo(1.0);
  }

  @Test
  void handoverConfirmedCounter_completeWithCodeMovesHandoverAndCompleted_recompleteMovesNothing() {
    Order order = paidOrder();
    order.setHandoverCodeHash(sha256Hex("123456"));
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(items.findById(ITEM_ID)).thenReturn(Optional.of(order.getItem()));
    stubOrderSave();
    stubItemSave();

    OrderDto dto = orderService.complete(SELLER_ID, false, ORDER_ID, "123456");

    assertThat(dto.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(count("orders.handover_confirmed")).isEqualTo(1.0);
    assertThat(count("orders.completed")).isEqualTo(1.0);
    assertThat(count("orders.shipped")).isZero();
    assertThat(count("orders.disputed")).isZero();

    // Idempotent re-complete returns the order — no second count.
    orderService.complete(SELLER_ID, false, ORDER_ID, "123456");
    assertThat(count("orders.handover_confirmed")).isEqualTo(1.0);
    assertThat(count("orders.completed")).isEqualTo(1.0);
  }

  @Test
  void handoverConfirmedCounter_adminOverrideAndReceiptConfirmationDoNotCount() {
    Order order = paidOrder();
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(items.findById(ITEM_ID)).thenReturn(Optional.of(order.getItem()));
    stubOrderSave();
    stubItemSave();

    // ADMIN override completes without a code: completed moves, handover does not.
    OrderDto dto = orderService.complete(SELLER_ID, true, ORDER_ID, null);
    assertThat(dto.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(count("orders.completed")).isEqualTo(1.0);
    assertThat(count("orders.handover_confirmed")).isZero();

    // Shipped-path receipt confirmation: completed moves, handover does not.
    Order shipped = paidOrder();
    shipped.setStatus(OrderStatus.SHIPPED);
    when(orders.findById(ORDER_ID)).thenReturn(Optional.of(shipped));
    when(items.findById(ITEM_ID)).thenReturn(Optional.of(shipped.getItem()));
    OrderDto receipt = orderService.confirmReceipt(BUYER_ID, ORDER_ID);
    assertThat(receipt.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(count("orders.completed")).isEqualTo(2.0);
    assertThat(count("orders.handover_confirmed")).isZero();
  }
}
