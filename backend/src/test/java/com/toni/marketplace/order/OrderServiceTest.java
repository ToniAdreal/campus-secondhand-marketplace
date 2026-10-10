package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.common.PaymentDeclinedException;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure unit tests for {@link OrderService} and {@link OrderCreationService}:
 * no Spring context, no database. Repositories and the idempotency service are
 * Mockito mocks. The orchestration entry point
 * {@link OrderService#createOrder} is tested with a mocked
 * {@link OrderCreationService}, while the creation guards are tested directly
 * against a real {@link OrderCreationService} instance.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock
  private OrderRepository orders;

  @Mock
  private ItemRepository items;

  @Mock
  private IdempotencyKeyService idempotency;

  @Mock
  private PaymentService payments;

  @Mock
  private OrderCreationService creationMock;

  private OrderService service;
  private OrderCreationService creation;

  @BeforeEach
  void setUp() {
    service = new OrderService(orders, items, idempotency, payments, creationMock);
    creation = new OrderCreationService(orders, items);
  }

  private static Item listing(long sellerId) {
    return new Item("Desk lamp", "a used desk lamp", 2500L, sellerId);
  }

  private static IdempotencyKeyService.Reservation.Created created(long recordId) {
    IdempotencyKey record = mock(IdempotencyKey.class);
    when(record.getId()).thenReturn(recordId);
    return new IdempotencyKeyService.Reservation.Created(record);
  }

  private static IdempotencyKeyService.Reservation.Replayed replayed(long orderId) {
    IdempotencyKey record = mock(IdempotencyKey.class);
    when(record.getOrderId()).thenReturn(orderId);
    return new IdempotencyKeyService.Reservation.Replayed(record);
  }

  // --- key validation (400 envelope guards) ---

  @Test
  void createOrder_blankKey_throws400AndReservesNothing() {
    assertThatThrownBy(() -> service.createOrder(7L, 3L, "   "))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
    verify(idempotency, never()).reserve(anyLong(), any(), anyLong());
  }

  @Test
  void createOrder_overlongKey_throws400AndReservesNothing() {
    assertThatThrownBy(() -> service.createOrder(7L, 3L, "k".repeat(65)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
    verify(idempotency, never()).reserve(anyLong(), any(), anyLong());
  }

  // --- OrderCreationService.create guards (direct, real bean) ---

  @Test
  void creation_unknownItem_throws404() {
    when(items.findById(3L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> creation.create(7L, 3L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    verify(orders, never()).save(any());
  }

  @Test
  void creation_nonAvailableItem_throws409() {
    Item lamp = listing(5L);
    lamp.setStatus(ItemStatus.RESERVED);
    when(items.findById(3L)).thenReturn(Optional.of(lamp));

    assertThatThrownBy(() -> creation.create(7L, 3L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(orders, never()).save(any());
    verify(items, never()).save(any());
  }

  @Test
  void creation_buyingOwnListing_throws422() {
    when(items.findById(3L)).thenReturn(Optional.of(listing(7L)));

    assertThatThrownBy(() -> creation.create(7L, 3L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(orders, never()).save(any());
  }

  @Test
  void creation_existingActiveOrder_throws409BeforeSaving() {
    when(items.findById(3L)).thenReturn(Optional.of(listing(5L)));
    when(orders.existsByItem_IdAndStatusIn(3L, List.of(OrderStatus.PENDING, OrderStatus.PAID)))
        .thenReturn(true);

    assertThatThrownBy(() -> creation.create(7L, 3L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(orders, never()).save(any());
  }

  @Test
  void creation_happyPath_snapshotsPriceAndStatusPending() {
    Item lamp = listing(5L);
    when(items.findById(3L)).thenReturn(Optional.of(lamp));
    when(orders.existsByItem_IdAndStatusIn(3L, List.of(OrderStatus.PENDING, OrderStatus.PAID)))
        .thenReturn(false);
    Order saved = new Order(lamp, 7L, lamp.getPriceCents());
    when(orders.save(any(Order.class))).thenReturn(saved);

    OrderDto dto = creation.create(7L, 3L);

    assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(dto.amountCents()).isEqualTo(2500L);
    assertThat(dto.buyerId()).isEqualTo(7L);
    // The listing is reserved as part of creation so the list view stops
    // offering it immediately.
    assertThat(lamp.getStatus()).isEqualTo(ItemStatus.RESERVED);
    verify(items).save(lamp);
  }

  // --- createOrder orchestration (mocked OrderCreationService) ---

  @Test
  void createOrder_replayedKey_returnsOriginalOrderWithoutCreating() {
    Order original = new Order(listing(5L), 7L, 2500L);
    var reservation = replayed(11L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);
    when(orders.findById(11L)).thenReturn(Optional.of(original));

    OrderDto dto = service.createOrder(7L, 3L, "key-1");

    assertThat(dto.buyerId()).isEqualTo(7L);
    verify(creationMock, never()).create(anyLong(), anyLong());
    verify(orders, never()).save(any());
    verify(idempotency, never()).complete(anyLong(), anyLong());
  }

  @Test
  void createOrder_insertRace_loserRereadsWinnersRecord() {
    Order original = new Order(listing(5L), 7L, 2500L);
    var reread = replayed(11L);
    when(idempotency.reserve(7L, "key-1", 3L))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));
    when(idempotency.reread(7L, "key-1", 3L)).thenReturn(reread);
    when(orders.findById(11L)).thenReturn(Optional.of(original));

    OrderDto dto = service.createOrder(7L, 3L, "key-1");

    assertThat(dto.buyerId()).isEqualTo(7L);
    verify(orders, never()).save(any());
  }

  @Test
  void createOrder_success_completesTheKey() {
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);

    OrderDto dto = new OrderDto(11L, 3L, 7L, OrderStatus.PENDING, 2500L, Instant.now(), null, null);
    when(creationMock.create(7L, 3L)).thenReturn(dto);

    OrderDto result = service.createOrder(7L, 3L, "key-1");

    assertThat(result).isSameAs(dto);
    verify(idempotency).complete(42L, 11L);
    verify(idempotency, never()).fail(anyLong());
  }

  @Test
  void createOrder_constraintRace_burnsKeyAndThrows409() {
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);

    when(creationMock.create(7L, 3L))
        .thenThrow(new DataIntegrityViolationException("uq_order_active_item"));

    assertThatThrownBy(() -> service.createOrder(7L, 3L, "key-1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(idempotency).fail(42L);
    verify(idempotency, never()).complete(anyLong(), anyLong());
  }

  @Test
  void createOrder_appFailure_burnsKeyAndRethrows() {
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);

    ResponseStatusException appFailure =
        new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
    when(creationMock.create(7L, 3L)).thenThrow(appFailure);

    assertThatThrownBy(() -> service.createOrder(7L, 3L, "key-1"))
        .isSameAs(appFailure);
    verify(idempotency).fail(42L);
    verify(idempotency, never()).complete(anyLong(), anyLong());
  }

  @Test
  void createOrder_itemVersionRace_burnsKeyAndThrows409() {
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);

    when(creationMock.create(7L, 3L))
        .thenThrow(new ObjectOptimisticLockingFailureException(Item.class, 3L));

    assertThatThrownBy(() -> service.createOrder(7L, 3L, "key-1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(idempotency).fail(42L);
    verify(idempotency, never()).complete(anyLong(), anyLong());
  }

  // --- pay (mock PSP capture) ---

  private static Order paidOrder(long orderId, long buyerId) {
    Item item = listing(9L);
    Order order = new Order(item, buyerId, 2500L);
    order.setStatus(OrderStatus.PAID);
    return order;
  }

  private static Order pendingOrder(long buyerId) {
    Item item = listing(9L);
    return new Order(item, buyerId, 2500L);
  }

  @Test
  void pay_unknownOrder_throws404() {
    when(orders.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.pay(7L, 99L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    verify(payments, never()).capture(any(), anyString(), any());
  }

  @Test
  void pay_nonBuyer_throws403AndCapturesNothing() {
    Order order = pendingOrder(7L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.pay(8L, 5L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(payments, never()).capture(any(), anyString(), any());
    verify(orders, never()).save(any());
  }

  @Test
  void pay_alreadyPaid_isIdempotentWithoutRecapture() {
    Order order = paidOrder(5L, 7L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));

    OrderDto dto = service.pay(7L, 5L);

    assertThat(dto.status()).isEqualTo(OrderStatus.PAID);
    verify(payments, never()).capture(any(), anyString(), any());
    verify(orders, never()).save(any());
  }

  @Test
  void pay_cancelledOrder_throws422() {
    Order order = pendingOrder(7L);
    order.setStatus(OrderStatus.CANCELLED);
    when(orders.findById(5L)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.pay(7L, 5L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(payments, never()).capture(any(), anyString(), any());
  }

  @Test
  void pay_declinedCapture_propagatesAndLeavesOrderPending() {
    Order order = pendingOrder(7L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(payments.capture(eq(order), anyString(), eq(PaymentService.DECLINE_TOKEN)))
        .thenThrow(new PaymentDeclinedException());

    assertThatThrownBy(() -> service.pay(7L, 5L, PaymentService.DECLINE_TOKEN))
        .isInstanceOf(PaymentDeclinedException.class);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(orders, never()).save(any());
  }

  @Test
  void pay_pendingOrder_capturesTransitionsAndReservesItem() {
    Order order = pendingOrder(7L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(payments.capture(eq(order), anyString(), any()))
        .thenReturn(new PaymentService.CaptureResult("cap_mock_x", 2500L));
    when(orders.save(order)).thenAnswer(inv -> inv.getArgument(0));

    OrderDto dto = service.pay(7L, 5L);

    assertThat(dto.status()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getItem().getStatus()).isEqualTo(ItemStatus.RESERVED);
    verify(payments).capture(eq(order), anyString(), any());
  }

  @Test
  void pay_versionConflictOnSave_throws409() {
    Order order = pendingOrder(7L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(payments.capture(eq(order), anyString(), any()))
        .thenReturn(new PaymentService.CaptureResult("cap_mock_x", 2500L));
    when(orders.save(order)).thenThrow(
        new org.springframework.orm.ObjectOptimisticLockingFailureException(Order.class, 5L));

    assertThatThrownBy(() -> service.pay(7L, 5L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
  }
}
