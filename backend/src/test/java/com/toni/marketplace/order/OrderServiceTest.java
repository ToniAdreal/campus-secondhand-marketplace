package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.common.PaymentDeclinedException;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import com.toni.marketplace.notification.NotificationService;
import com.toni.marketplace.notification.NotificationType;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
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

  // Backlog #212: collaborators for the metrics/audit/notification-aware
  // service below — the PIT driving suite's surviving mutants live in the
  // side effects the legacy 5-arg service wires to no-ops, so the kill
  // tests construct the full 12-arg service with mocks and a fixed clock.
  @Mock
  private OrderMetrics awareMetrics;

  @Mock
  private AuditService awareAudit;

  @Mock
  private NotificationService awareNotifications;

  @Mock
  private PaymentAttemptRecorder awareAttempts;

  @Mock
  private Timer.Sample sample;

  private static final Instant PAY_NOW = Instant.parse("2026-10-22T00:00:00Z");

  private OrderService service;
  private OrderCreationService creation;

  /** The full 13-arg service: mocked metrics/audit/notifications/attempts, fixed clock. */
  private OrderService awareService() {
    return new OrderService(orders, items, idempotency, payments, creationMock,
        awareMetrics, awareAudit, awareNotifications,
        Clock.fixed(PAY_NOW, ZoneOffset.UTC), null, null, awareAttempts, null);
  }

  private static String sha256Hex(String input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

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
    when(orders.existsByItem_IdAndStatusIn(3L, List.of(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DISPUTED)))
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
    when(orders.existsByItem_IdAndStatusIn(3L, List.of(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DISPUTED)))
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

    OrderDto dto = new OrderDto(11L, 3L, 7L, OrderStatus.PENDING, 2500L, Instant.now(), null, null, 0L, null);
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

  // --- Backlog #212: mutation-survivor kill tests (aware service) ---
  //
  // Each test below is targeted at named SURVIVED mutants from PIT run
  // 39735286602 (see docs/mutation.md). PIT cannot run in this sandbox,
  // so these tests are "targeted at" those mutants — the kill itself is
  // confirmed by the next CI backend-mutation run, not claimed here.

  @Test
  void pay_awareService_storesCaptureStampMintsHandoverAndRecordsSideEffects() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    // A stale handover lock from earlier failures: a successful pay must
    // reset the counter and clear the lock when it mints the fresh code.
    order.setHandoverFailedAttempts(3);
    order.setHandoverLockedUntil(PAY_NOW.plusSeconds(900));
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(awareMetrics.startPayTimer()).thenReturn(sample);
    when(payments.capture(eq(order), anyString(), any()))
        .thenReturn(new PaymentService.CaptureResult("cap_mock_212", 2500L));
    when(orders.save(order)).thenAnswer(inv -> inv.getArgument(0));

    OrderDto dto = aware.pay(7L, 5L);

    assertThat(dto.status()).isEqualTo(OrderStatus.PAID);
    // The PSP capture reference and the payment instant (fixed clock) land
    // on the order row, not just in the response.
    assertThat(dto.captureId()).isEqualTo("cap_mock_212");
    assertThat(order.getCaptureId()).isEqualTo("cap_mock_212");
    assertThat(order.getPaidAt()).isEqualTo(PAY_NOW);

    // The capture was called with the order's stored idempotency key.
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    verify(payments).capture(eq(order), keyCaptor.capture(), any());
    String captureKey = keyCaptor.getValue();
    assertThat(captureKey).isNotEmpty().startsWith("cap_");
    assertThat(captureKey).isEqualTo(order.getCaptureIdempotencyKey());

    // Handover: the DTO discloses the one-time plaintext; the row stores
    // only its SHA-256 (64 hex chars), attempts reset, lock cleared.
    assertThat(dto.handoverCode()).isNotNull().hasSize(6);
    assertThat(order.getHandoverCodeHash()).hasSize(64)
        .matches("[0-9a-f]{64}")
        .isEqualTo(sha256Hex(dto.handoverCode()));
    assertThat(order.getHandoverFailedAttempts()).isZero();
    assertThat(order.getHandoverLockedUntil()).isNull();

    verify(orders).flush();
    verify(awareAttempts).recordSucceeded(5L, captureKey);
    verify(payments).forgetCapture(captureKey);
    verify(awareMetrics).paid();
    verify(awareMetrics).stopPayTimer(sample, true);
    verify(awareAudit).record(7L, AuditAction.ORDER_PAID, AuditTargetType.ORDER, 5L);
    verify(awareNotifications).record(9L, NotificationType.ORDER_PAID, 5L);
  }

  @Test
  void pay_awareService_declinedCapture_countsDeclineStopsTimerAndRecordsAttempt() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(awareMetrics.startPayTimer()).thenReturn(sample);
    when(payments.capture(eq(order), anyString(), eq(PaymentService.DECLINE_TOKEN)))
        .thenThrow(new PaymentDeclinedException());

    assertThatThrownBy(() -> aware.pay(7L, 5L, PaymentService.DECLINE_TOKEN))
        .isInstanceOf(PaymentDeclinedException.class);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    verify(payments).capture(eq(order), keyCaptor.capture(), eq(PaymentService.DECLINE_TOKEN));
    verify(awareMetrics).paymentsDeclined();
    verify(awareMetrics, times(1)).stopPayTimer(sample, false);
    verify(awareMetrics, never()).paid();
    // The DECLINED attempt is recorded under the very key the PSP declined.
    verify(awareAttempts).recordDeclined(5L, keyCaptor.getValue());
    assertThat(keyCaptor.getValue()).startsWith("cap_");
  }

  @Test
  void pay_awareService_versionConflict_stopsPayTimerAsFailure() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(awareMetrics.startPayTimer()).thenReturn(sample);
    when(payments.capture(eq(order), anyString(), any()))
        .thenReturn(new PaymentService.CaptureResult("cap_mock_212", 2500L));
    when(orders.save(order)).thenThrow(
        new ObjectOptimisticLockingFailureException(Order.class, 5L));

    assertThatThrownBy(() -> aware.pay(7L, 5L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(awareMetrics, times(1)).stopPayTimer(sample, false);
    verify(awareMetrics, never()).paid();
    verify(awareMetrics, never()).paymentsDeclined();
  }

  @Test
  void createOrder_awareService_success_countsCreatedAndStopsTimerAsSuccess() {
    OrderService aware = awareService();
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);
    when(awareMetrics.startCreateTimer()).thenReturn(sample);
    OrderDto dto = new OrderDto(11L, 3L, 7L, OrderStatus.PENDING, 2500L,
        Instant.now(), null, null, 0L, null);
    when(creationMock.create(7L, 3L)).thenReturn(dto);

    OrderDto result = aware.createOrder(7L, 3L, "key-1");

    assertThat(result).isSameAs(dto);
    verify(awareMetrics).created();
    verify(awareMetrics, times(1)).stopCreateTimer(sample, true);
    verify(awareMetrics, never()).stopCreateTimer(any(), eq(false));
  }

  @Test
  void createOrder_awareService_appFailure_stopsTimerAsFailureAndNeverCountsCreated() {
    OrderService aware = awareService();
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);
    when(awareMetrics.startCreateTimer()).thenReturn(sample);
    ResponseStatusException appFailure =
        new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found");
    when(creationMock.create(7L, 3L)).thenThrow(appFailure);

    assertThatThrownBy(() -> aware.createOrder(7L, 3L, "key-1")).isSameAs(appFailure);
    verify(awareMetrics, times(1)).stopCreateTimer(sample, false);
    verify(awareMetrics, never()).created();
    verify(idempotency).fail(42L);
  }

  @Test
  void createOrder_awareService_constraintRace_stopsTimerAsFailureAndNeverCountsCreated() {
    OrderService aware = awareService();
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);
    when(awareMetrics.startCreateTimer()).thenReturn(sample);
    when(creationMock.create(7L, 3L))
        .thenThrow(new DataIntegrityViolationException("uq_order_active_item"));

    assertThatThrownBy(() -> aware.createOrder(7L, 3L, "key-1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(awareMetrics, times(1)).stopCreateTimer(sample, false);
    verify(awareMetrics, never()).created();
    verify(idempotency).fail(42L);
  }

  @Test
  void createOrder_awareService_itemVersionRace_stopsTimerAsFailureAndNeverCountsCreated() {
    OrderService aware = awareService();
    var reservation = created(42L);
    when(idempotency.reserve(7L, "key-1", 3L)).thenReturn(reservation);
    when(awareMetrics.startCreateTimer()).thenReturn(sample);
    when(creationMock.create(7L, 3L))
        .thenThrow(new ObjectOptimisticLockingFailureException(Item.class, 3L));

    assertThatThrownBy(() -> aware.createOrder(7L, 3L, "key-1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(awareMetrics, times(1)).stopCreateTimer(sample, false);
    verify(awareMetrics, never()).created();
    verify(idempotency).fail(42L);
  }

  @Test
  void createOrder_keyOfExactly64Chars_isAccepted() {
    // Boundary: MAX_KEY_LENGTH is inclusive — 64 chars passes validation
    // (the 65-char rejection is pinned by createOrder_overlongKey_*).
    String key64 = "k".repeat(64);
    var reservation = created(42L);
    when(idempotency.reserve(7L, key64, 3L)).thenReturn(reservation);
    OrderDto dto = new OrderDto(11L, 3L, 7L, OrderStatus.PENDING, 2500L,
        Instant.now(), null, null, 0L, null);
    when(creationMock.create(7L, 3L)).thenReturn(dto);

    OrderDto result = service.createOrder(7L, 3L, key64);

    assertThat(result).isSameAs(dto);
    verify(idempotency).complete(42L, 11L);
  }

  @Test
  void getOrderFor_buyer_latestAttemptDeclined_carriesPaymentFailureNote() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(awareAttempts.findLatest(5L)).thenReturn(Optional.of(
        new PaymentAttempt(5L, PaymentAttempt.Outcome.DECLINED, "cap_declined")));

    OrderDto dto = aware.getOrderFor(7L, false, 5L);

    assertThat(dto.lastPaymentFailure()).isEqualTo(OrderService.LAST_PAYMENT_FAILURE_NOTE);
  }

  @Test
  void getOrderFor_buyer_latestAttemptSucceeded_carriesNoNote() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(awareAttempts.findLatest(5L)).thenReturn(Optional.of(
        new PaymentAttempt(5L, PaymentAttempt.Outcome.SUCCEEDED, "cap_ok")));

    OrderDto dto = aware.getOrderFor(7L, false, 5L);

    assertThat(dto.lastPaymentFailure()).isNull();
  }

  @Test
  void getOrderFor_buyer_noRecordedAttempt_carriesNoNote() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(awareAttempts.findLatest(5L)).thenReturn(Optional.empty());

    OrderDto dto = aware.getOrderFor(7L, false, 5L);

    assertThat(dto.lastPaymentFailure()).isNull();
  }

  @Test
  void creation_happyPath_flushesInsideTheTransaction() {
    Item lamp = listing(5L);
    when(items.findById(3L)).thenReturn(Optional.of(lamp));
    when(orders.existsByItem_IdAndStatusIn(3L, List.of(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DISPUTED)))
        .thenReturn(false);
    Order saved = new Order(lamp, 7L, lamp.getPriceCents());
    when(orders.save(any(Order.class))).thenReturn(saved);

    OrderDto dto = creation.create(7L, 3L);

    assertThat(dto.status()).isEqualTo(OrderStatus.PENDING);
    // The deliberate in-transaction flush: a constraint/version failure on
    // the insert must surface here, not at commit.
    verify(orders).flush();
  }

  @Test
  void creation_expiredListing_throws409() {
    // OrderCreationService.create L103 is the lazy listing-expiry guard
    // (item.isExpiredAt): an AVAILABLE listing past its expiresAt is
    // already expired on read, so a direct create must 409 like the
    // not-AVAILABLE guard — confirmed from the source for backlog #212.
    Item lamp = listing(5L);
    lamp.setExpiresAt(Instant.now().minusSeconds(60));
    when(items.findById(3L)).thenReturn(Optional.of(lamp));

    assertThatThrownBy(() -> creation.create(7L, 3L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
    verify(orders, never()).save(any());
    verify(items, never()).save(any());
  }

  // --- Backlog #355: mutation-survivor kill tests (aware service) ---
  //
  // Each test below is targeted at named SURVIVED mutants from PIT run
  // 42434347952 (head 099aba0, 2026-11-08 — see docs/mutation.md), the
  // first CI backend-mutation run after the #345 targetTests widening.
  // PIT cannot run in this sandbox, so these tests are "targeted at"
  // those mutants — the kill itself is confirmed by the next CI
  // backend-mutation run, not claimed here.

  @Test
  void cancel_awareService_recordsSideEffectsAndReleasesListing() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    Item item = order.getItem();
    item.setStatus(ItemStatus.RESERVED);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(items.findById(any())).thenReturn(Optional.of(item));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    when(items.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));

    OrderDto dto = aware.cancel(7L, 5L);

    assertThat(dto.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(item.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
    verify(orders).flush();
    verify(awareMetrics).cancelled();
    verify(awareAudit).record(7L, AuditAction.ORDER_CANCELLED, AuditTargetType.ORDER, 5L);
    verify(awareNotifications).record(9L, NotificationType.ORDER_CANCELLED, 5L);
  }

  @Test
  void refund_awareService_fullRefundRecordsMoneyAndSideEffects() {
    OrderService aware = awareService();
    Item item = listing(9L);
    item.setStatus(ItemStatus.RESERVED);
    Order order = new Order(item, 7L, 2500L);
    order.setStatus(OrderStatus.PAID);
    order.setRefundedAmountCents(1000L);
    order.setRefundCount(1);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(items.findById(any())).thenReturn(Optional.of(item));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    when(items.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));
    when(payments.refund(eq(order), eq("rfd_5_2"), eq(1500L)))
        .thenReturn(new PaymentService.RefundResult("rfd_mock_1", 1500L));
    // Exactly 500 chars: the boundary the > 500 reason guard must accept.
    String reason500 = "r".repeat(500);

    OrderDto dto = aware.refund(9L, false, 5L, null, reason500);

    assertThat(dto.status()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(order.getRefundId()).isEqualTo("rfd_mock_1");
    assertThat(order.getRefundedAmountCents()).isEqualTo(2500L);
    assertThat(order.getRefundCount()).isEqualTo(2);
    assertThat(order.getRefundIdempotencyKey()).isEqualTo("rfd_5_2");
    assertThat(item.getStatus()).isEqualTo(ItemStatus.AVAILABLE);
    verify(payments).refund(eq(order), eq("rfd_5_2"), eq(1500L));
    verify(orders).flush();
    verify(payments).forgetRefund("rfd_5_2");
    verify(awareAttempts).recordRefunded(5L, "rfd_5_2", reason500);
    verify(awareMetrics).refunded();
    verify(awareAudit).record(9L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER, 5L);
    verify(awareNotifications).record(7L, NotificationType.ORDER_REFUNDED, 5L);
  }

  @Test
  void refund_awareService_partialRefundStaysPaidAndCountsNoRefundMetric() {
    OrderService aware = awareService();
    Item item = listing(9L);
    item.setStatus(ItemStatus.RESERVED);
    Order order = new Order(item, 7L, 2500L);
    order.setStatus(OrderStatus.PAID);
    order.setRefundedAmountCents(1000L);
    order.setRefundCount(1);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    when(payments.refund(eq(order), eq("rfd_5_2"), eq(500L)))
        .thenReturn(new PaymentService.RefundResult("rfd_mock_2", 500L));

    OrderDto dto = aware.refund(9L, false, 5L, 500L, "damaged in transit");

    assertThat(dto.status()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getRefundedAmountCents()).isEqualTo(1500L);
    assertThat(order.getRefundCount()).isEqualTo(2);
    assertThat(item.getStatus()).isEqualTo(ItemStatus.RESERVED);
    verify(awareMetrics, never()).refunded();
    verify(awareAudit).record(9L, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER, 5L);
    verify(awareNotifications).record(7L, NotificationType.ORDER_REFUNDED, 5L);
  }

  @Test
  void refund_zeroAmount_throws422AndCallsNoPsp() {
    OrderService aware = awareService();
    Order order = paidOrder(5L, 7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> aware.refund(9L, false, 5L, 0L, "no reason to refund nothing"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(payments, never()).refund(any(), anyString(), anyLong());
  }

  @Test
  void ship_awareService_recordsSideEffects() {
    OrderService aware = awareService();
    Order order = paidOrder(5L, 7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

    OrderDto dto = aware.ship(9L, false, 5L, "DHL", "TRACK-1");

    assertThat(dto.status()).isEqualTo(OrderStatus.SHIPPED);
    assertThat(dto.carrier()).isEqualTo("DHL");
    assertThat(dto.trackingNumber()).isEqualTo("TRACK-1");
    assertThat(dto.shippedAt()).isEqualTo(PAY_NOW);
    assertThat(order.getShippedAt()).isEqualTo(PAY_NOW);
    verify(orders).flush();
    verify(awareMetrics).shipped();
    verify(awareAudit).record(9L, AuditAction.ORDER_SHIPPED, AuditTargetType.ORDER, 5L);
    verify(awareNotifications).record(7L, NotificationType.ORDER_SHIPPED, 5L);
  }

  @Test
  void complete_awareService_sellerPathConfirmsHandoverAndRecordsSideEffects() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    Item item = order.getItem();
    item.setStatus(ItemStatus.RESERVED);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(items.findById(any())).thenReturn(Optional.of(item));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    when(items.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));
    when(awareMetrics.startPayTimer()).thenReturn(sample);
    when(payments.capture(eq(order), anyString(), any()))
        .thenReturn(new PaymentService.CaptureResult("cap_mock_355", 2500L));

    OrderDto paid = aware.pay(7L, 5L);
    assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
    assertThat(paid.handoverCode()).isNotNull().hasSize(6);
    // A stale (expired) lock from earlier failed guesses: completion must
    // still succeed and clear both the counter and the lock.
    order.setHandoverFailedAttempts(3);
    order.setHandoverLockedUntil(PAY_NOW.minusSeconds(60));

    OrderDto dto = aware.complete(9L, false, 5L, paid.handoverCode());

    assertThat(dto.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(order.getHandoverFailedAttempts()).isZero();
    assertThat(order.getHandoverLockedUntil()).isNull();
    assertThat(order.getCompletedAt()).isEqualTo(PAY_NOW);
    assertThat(item.getStatus()).isEqualTo(ItemStatus.SOLD);
    // The mint stamps the expiry (default 7-day TTL on the fixed clock).
    assertThat(order.getHandoverCodeExpiresAt()).isEqualTo(PAY_NOW.plus(Duration.ofDays(7)));
    verify(orders, times(2)).flush();
    verify(awareMetrics).completed();
    verify(awareMetrics).handoverConfirmed();
    verify(awareAudit).record(9L, AuditAction.ORDER_COMPLETED, AuditTargetType.ORDER, 5L);
    verify(awareNotifications).record(7L, NotificationType.ORDER_COMPLETED, 5L);
  }

  @Test
  void complete_adminOverride_neverCountsHandoverConfirmed() {
    OrderService aware = awareService();
    Order order = paidOrder(5L, 7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    Item item = order.getItem();
    item.setStatus(ItemStatus.RESERVED);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(items.findById(any())).thenReturn(Optional.of(item));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    when(items.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));

    OrderDto dto = aware.complete(1L, true, 5L, null);

    assertThat(dto.status()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(item.getStatus()).isEqualTo(ItemStatus.SOLD);
    verify(awareMetrics, never()).handoverConfirmed();
    verify(awareAudit).record(1L, AuditAction.ORDER_COMPLETED_ADMIN_OVERRIDE,
        AuditTargetType.ORDER, 5L);
  }

  @Test
  void complete_expiredHandoverCode_throws422() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    when(awareMetrics.startPayTimer()).thenReturn(sample);
    when(payments.capture(eq(order), anyString(), any()))
        .thenReturn(new PaymentService.CaptureResult("cap_mock_355", 2500L));

    OrderDto paid = aware.pay(7L, 5L);
    order.setHandoverCodeExpiresAt(PAY_NOW.minusSeconds(1));

    assertThatThrownBy(() -> aware.complete(9L, false, 5L, paid.handoverCode()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void regenerateHandoverCode_awareService_flushesAndRestampsExpiry() {
    OrderService aware = awareService();
    Order order = pendingOrder(7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));
    when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    when(awareMetrics.startPayTimer()).thenReturn(sample);
    when(payments.capture(eq(order), anyString(), any()))
        .thenReturn(new PaymentService.CaptureResult("cap_mock_355", 2500L));

    OrderDto paid = aware.pay(7L, 5L);
    OrderDto regenerated = aware.regenerateHandoverCode(7L, 5L);

    assertThat(regenerated.handoverCode()).isNotNull().hasSize(6)
        .isNotEqualTo(paid.handoverCode());
    assertThat(order.getHandoverCodeHash()).isEqualTo(sha256Hex(regenerated.handoverCode()));
    assertThat(order.getHandoverCodeExpiresAt()).isEqualTo(PAY_NOW.plus(Duration.ofDays(7)));
    verify(orders, times(2)).flush();
  }

  @Test
  void getSellerOrderFor_ownerAdminAndStranger() {
    Order order = paidOrder(5L, 7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    when(orders.findById(5L)).thenReturn(Optional.of(order));

    assertThat(service.getSellerOrderFor(9L, false, 5L).id()).isEqualTo(5L);
    assertThat(service.getSellerOrderFor(1L, true, 5L).id()).isEqualTo(5L);
    assertThatThrownBy(() -> service.getSellerOrderFor(8L, false, 5L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void listSellerOrders_nullStatusUsesUnfilteredQuery() {
    Order order = paidOrder(5L, 7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    Pageable pageable = Pageable.unpaged();
    Page<Order> page = new PageImpl<>(List.of(order));
    when(orders.findByItem_SellerId(9L, pageable)).thenReturn(page);

    Page<OrderDto> result = service.listSellerOrders(9L, null, pageable);

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).id()).isEqualTo(5L);
    verify(orders).findByItem_SellerId(9L, pageable);
    verify(orders, never()).findByItem_SellerIdAndStatus(anyLong(), any(), any());
  }

  @Test
  void listSellerOrders_statusUsesFilteredQuery() {
    Order order = paidOrder(5L, 7L);
    ReflectionTestUtils.setField(order, "id", 5L);
    Pageable pageable = Pageable.unpaged();
    Page<Order> page = new PageImpl<>(List.of(order));
    when(orders.findByItem_SellerIdAndStatus(9L, OrderStatus.PAID, pageable))
        .thenReturn(page);

    Page<OrderDto> result = service.listSellerOrders(9L, OrderStatus.PAID, pageable);

    assertThat(result.getContent()).hasSize(1);
    verify(orders).findByItem_SellerIdAndStatus(9L, OrderStatus.PAID, pageable);
    verify(orders, never()).findByItem_SellerId(anyLong(), any());
  }
}
