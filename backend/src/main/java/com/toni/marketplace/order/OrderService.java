package com.toni.marketplace.order;

import com.toni.marketplace.audit.AuditAction;
import com.toni.marketplace.common.PaymentDeclinedException;
import com.toni.marketplace.audit.AuditService;
import com.toni.marketplace.audit.AuditTargetType;
import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

  private static final int MAX_KEY_LENGTH = 64;

  private final OrderRepository orders;
  private final ItemRepository items;
  private final IdempotencyKeyService idempotency;
  private final PaymentService payments;
  /**
   * The transactional creation step. A separate bean (not a method on this
   * class) so the {@code @Transactional} proxy actually applies — calling a
   * {@code @Transactional} method on {@code this} would bypass the proxy and
   * silently drop the transaction (see {@link OrderCreationService}).
   */
  private final OrderCreationService creation;
  private final OrderMetrics metrics;
  private final AuditService audit;
  private final Clock clock;

  @Autowired
  public OrderService(OrderRepository orders, ItemRepository items,
                     IdempotencyKeyService idempotency, PaymentService payments,
                     OrderCreationService creation, OrderMetrics metrics,
                     AuditService audit, Clock clock) {
    this.orders = orders;
    this.items = items;
    this.idempotency = idempotency;
    this.payments = payments;
    this.creation = creation;
    this.metrics = metrics;
    this.audit = audit;
    this.clock = clock;
  }

  /** Legacy constructor for direct unit tests that do not assert on metrics. */
  public OrderService(OrderRepository orders, ItemRepository items,
                     IdempotencyKeyService idempotency, PaymentService payments,
                     OrderCreationService creation) {
    this(orders, items, idempotency, payments, creation, OrderMetrics.noop(),
        AuditService.noop(), Clock.systemDefaultZone());
  }

  /**
   * Clock accessor with a system fallback, mirroring {@link #metrics()}:
   * direct unit tests that construct this service by hand must never fail
   * on the clock.
   */
  private Clock clock() {
    return clock != null ? clock : Clock.systemDefaultZone();
  }

  /**
   * Audit accessor with a no-op fallback, mirroring {@link #metrics()}:
   * direct unit tests that construct this service by hand must never fail
   * on the trail.
   */
  private AuditService audit() {
    return audit != null ? audit : AuditService.noop();
  }

  /**
   * Metrics accessor with a no-op fallback, mirroring AuthService:
   * Mockito {@code @InjectMocks} in older unit tests constructs this
   * service without an OrderMetrics mock, leaving the field null —
   * counting must never break an order transition.
   */
  private OrderMetrics metrics() {
    return metrics != null ? metrics : OrderMetrics.noop();
  }

  /**
   * Creates an order idempotently, Stripe-style key semantics:
   * <ul>
   *   <li>Same key + same item → the original order is returned; the order
   *       table gains no second row, even across process restarts or client
   *       timeouts.</li>
   *   <li>Same key + different item → 422: the key is bound to the payload
   *       it first carried, so a recycled key cannot silently return the
   *       wrong order.</li>
   *   <li>Missing or blank key → 400; key longer than 64 chars → 400.</li>
   * </ul>
   * The key record and the order live in separate transactions (see
   * {@link IdempotencyKeyService}): a retried request first checks the key,
   * and only a brand-new key reaches order creation.
   */
  public OrderDto createOrder(Long buyerId, Long itemId, String idempotencyKey) {
    String key = validateKey(idempotencyKey);

    IdempotencyKeyService.Reservation reservation;
    try {
      reservation = idempotency.reserve(buyerId, key, itemId);
    } catch (DataIntegrityViolationException e) {
      // Lost the same-key insert race: the winner's reservation has
      // committed; re-read it and act on its state.
      reservation = idempotency.reread(buyerId, key, itemId);
    }

    if (reservation instanceof IdempotencyKeyService.Reservation.Replayed replayed) {
      return getOrder(replayed.record().getOrderId());
    }
    IdempotencyKey record =
        ((IdempotencyKeyService.Reservation.Created) reservation).record();

    try {
      // Runs in its own transaction on the OrderCreationService proxy (see
      // its javadoc): the listing flip and the order insert commit together.
      OrderDto created = creation.create(buyerId, itemId);
      // Count transitions, not calls (#93): only a fresh creation lands
      // here — a replayed key returned above, a failed creation throws.
      metrics().created();
      idempotency.complete(record.getId(), created.id());
      return created;
    } catch (DataIntegrityViolationException e) {
      // Lost the uq_order_active_item race: a concurrent request created the
      // item's active order first. The creation transaction rolled back; the
      // client retries with a NEW key (this one is burned as failed).
      idempotency.fail(record.getId());
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "item already has an active order");
    } catch (ObjectOptimisticLockingFailureException e) {
      // Lost the item-status race: a concurrent request flipped the item to
      // RESERVED first, bumping its @Version, so this request's status flip
      // failed fast on the stale version. Same outcome as the constraint
      // race above: 409, key burned, client retries with a NEW key.
      idempotency.fail(record.getId());
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "item already has an active order");
    } catch (RuntimeException e) {
      idempotency.fail(record.getId());
      throw e;
    }
  }

  @Transactional(readOnly = true)
  public OrderDto getOrder(Long orderId) {
    return orders.findById(orderId)
        .map(OrderDto::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
            "idempotency record points at a missing order"));
  }

  /**
   * Lists one buyer's orders (newest first — enforced by the controller's
   * default sort). Scoped strictly to the caller's own {@code buyerId}, so
   * one buyer can never page into another's orders.
   */
  @Transactional(readOnly = true)
  public Page<OrderDto> listOrders(Long buyerId, Pageable pageable) {
    return listOrders(buyerId, null, pageable);
  }

  /**
   * Status-filtered variant of {@link #listOrders(Long, Pageable)}
   * (backlog #123): a {@code null} status keeps the unfiltered behaviour;
   * a non-null status is one of the allowlisted {@link OrderStatus} values
   * (the controller binds the query parameter to the enum, so anything
   * else is rejected 400 before reaching here). Pagination totals reflect
   * the filtered set because the predicate lives in the repository query.
   */
  @Transactional(readOnly = true)
  public Page<OrderDto> listOrders(Long buyerId, OrderStatus status, Pageable pageable) {
    if (status == null) {
      return orders.findByBuyerId(buyerId, pageable).map(OrderDto::from);
    }
    return orders.findByBuyerIdAndStatus(buyerId, status, pageable).map(OrderDto::from);
  }

  /**
   * Lists orders placed on the caller's own listings (newest first —
   * enforced by the controller's default sort). Scoped strictly to listings
   * whose {@code sellerId} is the caller, so a seller can never page into
   * another seller's orders. Unlike {@link #getSellerOrderFor(Long, boolean,
   * Long)}, ADMIN gets no bypass here: admins have
   * {@code GET /api/orders/{id}} for any single order, and expanding the
   * list to "all orders" would change the endpoint's documented scope.
   */
  @Transactional(readOnly = true)
  public Page<OrderDto> listSellerOrders(Long sellerId, Pageable pageable) {
    return listSellerOrders(sellerId, null, pageable);
  }

  /**
   * Status-filtered variant of {@link #listSellerOrders(Long, Pageable)}
   * (backlog #123) — same scoping rules (own listings only, no ADMIN
   * bypass), plus the optional status predicate; {@code null} keeps the
   * unfiltered behaviour.
   */
  @Transactional(readOnly = true)
  public Page<OrderDto> listSellerOrders(Long sellerId, OrderStatus status,
                                         Pageable pageable) {
    if (status == null) {
      return orders.findByItem_SellerId(sellerId, pageable).map(OrderDto::from);
    }
    return orders.findByItem_SellerIdAndStatus(sellerId, status, pageable)
        .map(OrderDto::from);
  }

  /** The {@code [from, to)} instant window of one calendar month. */
  record MonthWindow(YearMonth month, Instant from, Instant to) {}

  /**
   * Month window for {@code now} in {@code zone}: from the first instant
   * of the month containing {@code now} to the first instant of the next
   * month. Package-private and static so the boundary arithmetic is
   * unit-testable with a fixed instant and an explicit zone.
   */
  static MonthWindow monthWindow(Instant now, ZoneId zone) {
    YearMonth month = YearMonth.from(now.atZone(zone));
    Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
    Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
    return new MonthWindow(month, from, to);
  }

  /**
   * Sales summary for one seller (backlog #108): per-status counts and
   * gross over every order on the caller's listings, plus the same
   * headline numbers for the current calendar month. Gross sums the
   * orders' {@code amountCents} price snapshots (see
   * {@link SellerSalesSummaryDto}), so a later listing price edit never
   * rewrites it. Scoped strictly to the caller's listings like
   * {@link #listSellerOrders(Long, Pageable)} — no ADMIN bypass — and a
   * seller with no orders gets zeros, not an error.
   *
   * <p>The month window uses the injected {@link Clock} for "now" and
   * the server-local zone ({@link ZoneId#systemDefault()}) for the
   * calendar boundary, as documented on {@link SellerSalesSummaryDto}.
   */
  @Transactional(readOnly = true)
  public SellerSalesSummaryDto sellerSalesSummary(Long sellerId) {
    MonthWindow window = monthWindow(clock().instant(), ZoneId.systemDefault());
    return SellerSalesSummaryDto.from(
        orders.summarizeBySellerId(sellerId),
        orders.summarizeBySellerIdBetween(sellerId, window.from(), window.to()),
        window.month());
  }

  /**
   * Reads a single order for the buyer who placed it — or for an ADMIN.
   * Unknown id → 404; anyone else → 403. The 403 deliberately mirrors
   * {@link #pay(Long, Long)}: an order id never leaks whether the caller
   * could have paid for it.
   */
  @Transactional(readOnly = true)
  public OrderDto getOrderFor(Long callerId, boolean callerIsAdmin, Long orderId) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!callerIsAdmin && !order.getBuyerId().equals(callerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "order does not belong to you");
    }
    return OrderDto.from(order);
  }

  /**
   * Reads a single order for the seller whose listing the order is on — or
   * for an ADMIN. Unknown id → 404; an order on somebody else's listing →
   * 403. The admin bypass mirrors {@link #getOrderFor(Long, boolean, Long)};
   * the list endpoint {@link #listSellerOrders(Long, Pageable)} stays
   * strictly scoped to the caller's own listings even for admins.
   */
  @Transactional(readOnly = true)
  public OrderDto getSellerOrderFor(Long callerId, boolean callerIsAdmin, Long orderId) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!callerIsAdmin && !order.getItem().getSellerId().equals(callerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "order is not on one of your listings");
    }
    return OrderDto.from(order);
  }

  /**
   * Order-scoped PSP idempotency keys (backlog #65): generated once per
   * order transition and stored on the order row, so a retry after a crash
   * between the PSP call and the commit reuses the stored key and the PSP
   * seam replays the original result instead of issuing a second logical
   * capture/refund. Capture and refund keys are distinct strings in
   * distinct namespaces. The order is managed inside the surrounding
   * transaction, so setting the field persists at flush — no explicit save
   * needed.
   */
  private String captureKeyFor(Order order) {
    if (order.getCaptureIdempotencyKey() == null) {
      order.setCaptureIdempotencyKey(
          "cap_" + UUID.randomUUID().toString().replace("-", ""));
    }
    return order.getCaptureIdempotencyKey();
  }

  private String refundKeyFor(Order order) {
    if (order.getRefundIdempotencyKey() == null) {
      order.setRefundIdempotencyKey(
          "rfd_" + UUID.randomUUID().toString().replace("-", ""));
    }
    return order.getRefundIdempotencyKey();
  }

  private String validateKey(String key) {    if (key == null || key.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Idempotency-Key must not be blank");
    }
    String trimmed = key.trim();
    if (trimmed.length() > MAX_KEY_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Idempotency-Key too long (max 64 characters)");
    }
    return trimmed;
  }

  /**
   * Cancels an order: PENDING → CANCELLED. Rules:
   * <ul>
   *   <li>Only the order's buyer may cancel; anyone else gets 403. The check
   *       runs before anything else, mirroring {@link #pay(Long, Long)}, so
   *       an order id never leaks what the caller may do with it.</li>
   *   <li>Only PENDING orders can be cancelled. PAID orders → 422 ("cancel the
   *       mock payment instead" via {@link #refund(Long, boolean, Long)} —
   *       cancellation after capture is out of scope); terminal
   *       (COMPLETED/CANCELLED) orders → 422, except that re-cancelling an
   *       already-CANCELLED order is idempotent and simply returns the
   *       order.</li>
   *   <li>The listing flips RESERVED → AVAILABLE in the same transaction, so
   *       the list view offers the item again. Before this endpoint a PENDING
   *       order stranded the listing in RESERVED forever — there was no
   *       release path. The flip only runs when the item is actually
   *       RESERVED: a listing the seller already marked SOLD (or that is
   *       AVAILABLE from before order-creation reservations existed) is left
   *       alone. Once the order is terminal the
   *       {@code uq_order_active_item} guard releases, so the buyer may order
   *       the item again.</li>
   *   <li>The flip goes through {@link ItemRepository#save} (merge), the same
   *       path as {@link OrderCreationService#create}. The merge bumps
   *       {@code Item.@Version}, so a cancel-vs-pay race fails fast on the
   *       stale version: whichever of the two transactions commits second
   *       loses the order-row version check and gets 409 instead of both
   *       writing.</li>
   * </ul>
   */
  @Transactional
  public OrderDto cancel(Long buyerId, Long orderId) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!order.getBuyerId().equals(buyerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the buyer can cancel this order");
    }
    if (order.getStatus() == OrderStatus.CANCELLED) {
      return OrderDto.from(order);
    }
    if (order.getStatus() == OrderStatus.PAID) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "order is PAID — cancel the mock payment instead via "
              + "POST /api/orders/{id}/refund");
    }
    if (order.getStatus() != OrderStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "order is " + order.getStatus() + ", cancellation is not allowed");
    }
    try {
      order.setStatus(OrderStatus.CANCELLED);
      Item item = items.findById(order.getItem().getId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
              "order points at a missing item"));
      if (item.getStatus() == ItemStatus.RESERVED) {
        item.setStatus(ItemStatus.AVAILABLE);
        items.save(item);
      }
      OrderDto dto = OrderDto.from(orders.save(order));
      // Flush deliberately inside the transaction (not at commit), mirroring
      // pay(): a concurrent transition of the order row between read and
      // write surfaces here as ObjectOptimisticLockingFailureException and is
      // mapped to 409. Without the flush the version check would happen at
      // commit, outside this try/catch, and the caller would see a 500.
      orders.flush();
      metrics().cancelled();
      // Backlog #122: audit after the flush, inside this transaction — a
      // version conflict (409) or any later rollback writes no row, and
      // the idempotent re-cancel above returns before this point.
      audit().record(buyerId, AuditAction.ORDER_CANCELLED, AuditTargetType.ORDER, orderId);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
  }

  /**
   * Cancels one stale PENDING order and releases its listing — the per-row
   * step of the stale-order expiry job (see
   * {@link PendingOrderExpiryService}). Rules:
   * <ul>
   *   <li>Only PENDING orders older than {@code cutoff} are touched. The
   *       status and age are re-checked inside this transaction: a
   *       concurrent pay/complete/cancel that committed after the job's
   *       worklist scan simply makes this method return {@code false},
   *       never double-transitions.</li>
   *   <li>The listing flips RESERVED → AVAILABLE in the same transaction, so
   *       the list view offers the item again. The flip only runs when the
   *       item is actually RESERVED: a listing the seller already marked
   *       SOLD by hand (PATCH /api/items/{id}/status) is left alone, while
   *       the order is still cancelled (it can no longer be completed).</li>
   *   <li>The flip goes through {@link ItemRepository#save} (merge), the
   *       same path as {@link #cancel(Long, Long)} and
   *       {@link OrderCreationService#create}. The merge bumps
   *       {@code Item.@Version}, and the in-transaction
   *       {@code orders.flush()} surfaces a concurrent order-row transition
   *       as {@code ObjectOptimisticLockingFailureException}, which the job
   *       catches per row and treats as "the concurrent transition won" —
   *       skipped, no retry, no crash loop.</li>
   * </ul>
   *
   * <p>Deliberately NO audit row (backlog #122): expiry is a system
   * transition with no actor, and stamping the buyer as the actor of an
   * {@code ORDER_CANCELLED} row would falsify the trail. Buyer cancels via
   * {@link #cancel(Long, Long)} are audited; expiries are not.
   *
   * @return {@code true} when the order was expired by this call,
   *         {@code false} when it was already non-PENDING or not stale.
   */
  @Transactional
  public boolean expireStaleOrder(Long orderId, Instant cutoff) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new IllegalStateException(
            "order " + orderId + " vanished from the expiry worklist"));
    if (order.getStatus() != OrderStatus.PENDING || !order.getCreatedAt().isBefore(cutoff)) {
      return false;
    }
    order.setStatus(OrderStatus.CANCELLED);
    Item item = items.findById(order.getItem().getId())
        .orElseThrow(() -> new IllegalStateException(
            "order " + orderId + " points at a missing item"));
    if (item.getStatus() == ItemStatus.RESERVED) {
      item.setStatus(ItemStatus.AVAILABLE);
      items.save(item);
    }
    orders.save(order);
    // Flush deliberately inside the transaction (not at commit), mirroring
    // pay()/cancel(): a concurrent transition of the order row between read
    // and write surfaces here as ObjectOptimisticLockingFailureException.
    // Without the flush the version check would happen at commit, outside
    // the job's per-row try/catch, and the batch would die on the first
    // race instead of skipping the row.
    orders.flush();
    metrics().expired();
    return true;
  }

  /**
   * Captures payment for an order (mock PSP — no real money moves) and
   * transitions it PENDING → PAID. Rules:
   * <ul>
   *   <li>Only the order's buyer may pay; anyone else gets 403. The check
   *       runs before anything else so an order id never leaks whether the
   *       caller could have paid.</li>
   *   <li>Paying an already-PAID order is idempotent: it returns the order
   *       without a second capture (no double-charge).</li>
   *   <li>Terminal orders (COMPLETED/CANCELLED) cannot be paid → 422.</li>
   *   <li>The listing flips AVAILABLE → RESERVED in the same transaction, so
   *       the list view stops offering an item that has just been paid for.
   *       (Since order creation now reserves the listing, this is usually a
   *       no-op; it still enforces the invariant for orders created before
   *       that change.)</li>
   *   <li>A concurrent modification of the order row between read and write
   *       surfaces as {@code ObjectOptimisticLockingFailureException} from
   *       the {@code @Version} field and is mapped to 409, fail-fast.
   *       The capture carries an order-scoped idempotency key (see
   *       {@link #captureKeyFor(Order)}): on a pay race the losing thread
   *       still calls {@link PaymentService#capture} before its version
   *       check fails at flush, but the key is per-order, so the loser's
   *       pre-flush call replays the winner's already-stored capture
   *       result instead of issuing a second logical capture — the mock is
   *       side-effect-free either way, and a real PSP would apply the same
   *       replay. Backlog #65 closed the declared follow-up.</li>
   *   <li>A declined capture (backlog #92 — the buyer passes the mock's
   *       documented {@code tok_decline} test token) surfaces as
   *       {@code PaymentDeclinedException} → 402 in the envelope. The
   *       exception rolls this transaction back, so the order stays
   *       PENDING, the listing stays RESERVED, and the capture key the
   *       PSP seam declined under is neither remembered by the mock nor
   *       persisted on the row — a retry with a succeeding token captures
   *       normally. The decline itself is counted on
   *       {@code payments.declined} (#115, see {@link OrderMetrics}).</li>
   * </ul>
   */
  @Transactional
  public OrderDto pay(Long buyerId, Long orderId) {
    return pay(buyerId, orderId, null);
  }

  /**
   * {@link #pay(Long, Long)} with an explicit mock payment token (see
   * {@link PayRequest}); {@code null} uses the mock's default succeeding
   * token.
   */
  @Transactional
  public OrderDto pay(Long buyerId, Long orderId, String paymentToken) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!order.getBuyerId().equals(buyerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the buyer can pay for this order");
    }
    if (order.getStatus() == OrderStatus.PAID) {
      return OrderDto.from(order);
    }
    if (order.getStatus() == OrderStatus.COMPLETED
        || order.getStatus() == OrderStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "order is " + order.getStatus() + ", payment is not allowed");
    }
    try {
      // Backlog #114: persist the PSP capture reference in the same
      // transaction as the transition, so a paid order always names the
      // reference it would be reconciled by. A declined capture throws
      // before this assignment (and rolls the transaction back), so it
      // stores nothing; an idempotent re-pay returned above, before any
      // fresh capture, so the stored id is never replaced.
      PaymentService.CaptureResult capture =
          payments.capture(order, captureKeyFor(order), paymentToken);
      order.setCaptureId(capture.captureId());
      order.setStatus(OrderStatus.PAID);
      order.getItem().setStatus(ItemStatus.RESERVED);
      OrderDto dto = OrderDto.from(orders.save(order));
      // Flush deliberately inside the transaction (not at commit): the
      // UPDATE runs now, so a concurrent modification of the order row
      // between read and write surfaces here as
      // ObjectOptimisticLockingFailureException and is mapped to 409.
      // Without the flush the version check would happen at commit, outside
      // this try/catch, and the caller would see a 500 instead.
      orders.flush();
      metrics().paid();
      // Backlog #122: audit after the flush, inside this transaction — a
      // version conflict (409) or any later rollback writes no row, the
      // idempotent re-pay above returns before this point, and a declined
      // capture throws before reaching it (and rolls back besides).
      audit().record(buyerId, AuditAction.ORDER_PAID, AuditTargetType.ORDER, orderId);
      return dto;
    } catch (PaymentDeclinedException e) {
      // Backlog #115: count the decline at exactly this point. The
      // exception rolls the transaction back (order stays PENDING), but
      // a Micrometer counter is not transactional — the increment
      // survives the rollback, which is the point: the decline happened
      // even though no state transition did. A 409 race loss lands in
      // the catch below and is never counted as a decline.
      metrics().paymentsDeclined();
      throw e;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
  }

  /**
   * Marks an order COMPLETED: the seller confirms the handoff after the
   * buyer paid. Rules:
   * <ul>
   *   <li>Only the listing's seller (or an ADMIN) may complete; the buyer
   *       and anyone else gets 403. The check runs before anything else,
   *       mirroring {@link #pay(Long, Long)}, so an order id never leaks
   *       whether the caller could have paid for it.</li>
   *   <li>Only PAID orders can be completed. PENDING/CANCELLED orders →
   *       422; a re-complete of an already-COMPLETED order is idempotent and
   *       simply returns the order.</li>
   *   <li>The listing flips RESERVED → SOLD in the same transaction, so the
   *       list view stops offering a paid-for item. Without this endpoint a
   *       PAID order stranded its listing in RESERVED forever — the mirror
   *       image of the PENDING strand bug {@link #cancel(Long, Long)}
   *       fixed. The flip only runs when the item is actually RESERVED: a
   *       listing the seller already marked SOLD by hand (PATCH
   *       /api/items/{id}/status) is left alone.</li>
   *   <li>The flip goes through {@link ItemRepository#save} (merge), the same
   *       path as {@link #cancel(Long, Long)} and
   *       {@link OrderCreationService#create}. The merge bumps
   *       {@code Item.@Version}, and the in-transaction
   *       {@code orders.flush()} surfaces a concurrent order-row transition
   *       as {@code ObjectOptimisticLockingFailureException}, mapped to 409
   *       fail-fast.</li>
   * </ul>
   */
  @Transactional
  public OrderDto complete(Long callerId, boolean callerIsAdmin, Long orderId) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!callerIsAdmin && !order.getItem().getSellerId().equals(callerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the seller can complete this order");
    }
    if (order.getStatus() == OrderStatus.COMPLETED) {
      return OrderDto.from(order);
    }
    if (order.getStatus() != OrderStatus.PAID) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "order is " + order.getStatus() + ", completion is not allowed");
    }
    try {
      order.setStatus(OrderStatus.COMPLETED);
      Item item = items.findById(order.getItem().getId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
              "order points at a missing item"));
      if (item.getStatus() == ItemStatus.RESERVED) {
        item.setStatus(ItemStatus.SOLD);
        items.save(item);
      }
      OrderDto dto = OrderDto.from(orders.save(order));
      // Flush deliberately inside the transaction (not at commit), mirroring
      // pay()/cancel(): a concurrent transition of the order row between read
      // and write surfaces here as ObjectOptimisticLockingFailureException
      // and is mapped to 409. Without the flush the version check would
      // happen at commit, outside this try/catch, and the caller would see a
      // 500.
      orders.flush();
      metrics().completed();
      // Backlog #98: audit after the flush, inside this transaction — a
      // version conflict (409) or any later rollback writes no row, and
      // the idempotent re-complete above returns before this point.
      audit().record(callerId, AuditAction.ORDER_COMPLETED, AuditTargetType.ORDER, orderId);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
  }

  /**
   * Refunds a captured order (mock PSP — see {@link PaymentService}):
   * PAID → REFUNDED. Rules:
   * <ul>
   *   <li>Only the listing's seller (or an ADMIN) may refund; the buyer and
   *       anyone else gets 403. This is a deliberate design decision: the
   *       buyer cannot self-refund after capture — capture settles money
   *       with the seller, so releasing the refund is the seller's (or a
   *       platform admin's) call. The check runs before anything else,
   *       mirroring {@link #pay(Long, Long)}, so an order id never leaks
   *       whether the caller could have acted on it.</li>
   *   <li>Only PAID orders can be refunded. PENDING/CANCELLED/COMPLETED
   *       orders → 422; a re-refund of an already-REFUNDED order is
   *       idempotent and simply returns the order without calling the PSP
   *       seam again (the mock counts refund calls — see
   *       {@link PaymentService#refundsIssued()} — and tests pin the
   *       single-invocation guarantee).</li>
   *   <li>The listing flips RESERVED → AVAILABLE in the same transaction, so
   *       the list view offers the refunded item again. The flip only runs
   *       when the item is actually RESERVED: a listing the seller already
   *       marked SOLD by hand (PATCH /api/items/{id}/status) is left alone.
   *       Once the order is terminal the {@code uq_order_active_item} guard
   *       releases, so the buyer may order the item again.</li>
   *   <li>The flip goes through {@link ItemRepository#save} (merge), the same
   *       path as {@link #cancel(Long, Long)} and
   *       {@link OrderCreationService#create}. The merge bumps
   *       {@code Item.@Version}, and the in-transaction
   *       {@code orders.flush()} surfaces a concurrent order-row transition
   *       as {@code ObjectOptimisticLockingFailureException}, mapped to 409
   *       fail-fast. The refund carries an order-scoped idempotency key
   *       (see {@link #refundKeyFor(Order)}), mirroring
   *       {@link #pay(Long, Long)}: on a refund race the loser's pre-flush
   *       call replays the winner's stored refund result instead of
   *       issuing a second logical refund (backlog #65).</li>
   * </ul>
   */
  @Transactional
  public OrderDto refund(Long callerId, boolean callerIsAdmin, Long orderId) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (!callerIsAdmin && !order.getItem().getSellerId().equals(callerId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "only the seller can refund this order");
    }
    if (order.getStatus() == OrderStatus.REFUNDED) {
      return OrderDto.from(order);
    }
    if (order.getStatus() != OrderStatus.PAID) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "order is " + order.getStatus() + ", refund is not allowed");
    }
    try {
      // Backlog #114: persist the PSP refund reference in the same
      // transaction as the transition, mirroring pay()'s capture id. An
      // idempotent re-refund returned above, before any fresh refund
      // call, so the stored id is never replaced.
      PaymentService.RefundResult refund =
          payments.refund(order, refundKeyFor(order));
      order.setRefundId(refund.refundId());
      order.setStatus(OrderStatus.REFUNDED);
      Item item = items.findById(order.getItem().getId())
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
              "order points at a missing item"));
      if (item.getStatus() == ItemStatus.RESERVED) {
        item.setStatus(ItemStatus.AVAILABLE);
        items.save(item);
      }
      OrderDto dto = OrderDto.from(orders.save(order));
      // Flush deliberately inside the transaction (not at commit), mirroring
      // pay()/cancel()/complete(): a concurrent transition of the order row
      // between read and write surfaces here as
      // ObjectOptimisticLockingFailureException and is mapped to 409.
      // Without the flush the version check would happen at commit, outside
      // this try/catch, and the caller would see a 500 instead.
      orders.flush();
      metrics().refunded();
      // Backlog #98: audit after the flush, inside this transaction — a
      // version conflict (409) or any later rollback writes no row, and
      // the idempotent re-refund above returns before this point.
      audit().record(callerId, AuditAction.ORDER_REFUNDED, AuditTargetType.ORDER, orderId);
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
  }
}
