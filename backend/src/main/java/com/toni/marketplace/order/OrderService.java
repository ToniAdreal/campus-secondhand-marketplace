package com.toni.marketplace.order;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.List;
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
  private static final List<OrderStatus> ACTIVE =
      List.of(OrderStatus.PENDING, OrderStatus.PAID);

  private final OrderRepository orders;
  private final ItemRepository items;
  private final IdempotencyKeyService idempotency;
  private final PaymentService payments;

  public OrderService(OrderRepository orders, ItemRepository items,
                     IdempotencyKeyService idempotency, PaymentService payments) {
    this.orders = orders;
    this.items = items;
    this.idempotency = idempotency;
    this.payments = payments;
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
      OrderDto created = createOrderTx(buyerId, itemId);
      idempotency.complete(record.getId(), created.id());
      return created;
    } catch (DataIntegrityViolationException e) {
      // Lost the uq_order_active_item race: a concurrent request created the
      // item's active order first. The transaction rolled back; the client
      // retries with a NEW key (this one is burned as failed).
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
    return orders.findByBuyerId(buyerId, pageable).map(OrderDto::from);
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
   * The actual creation: item must exist, be AVAILABLE, not belong to the
   * buyer, and have no active order. The application-level
   * {@code existsByItem_IdAndStatusIn} check gives a friendly 409; the
   * {@code uq_order_active_item} database constraint stays the final arbiter
   * under races (mapped to 409 by the caller).
   *
   * <p>The listing flips AVAILABLE → RESERVED as part of creation, so the
   * list view (which projects {@code Item.status}) stops offering an item
   * the moment someone orders it — not only once it is paid for. The flip
   * goes through {@link ItemRepository#save} (merge): the item was read by
   * its own repository transaction and is detached here, and the merge
   * bumps {@code Item.@Version}, so two concurrent creations racing on one
   * listing fail fast on the stale version
   * ({@code ObjectOptimisticLockingFailureException} → 409 in the caller)
   * instead of both inserting.
   */
  @Transactional
  public OrderDto createOrderTx(Long buyerId, Long itemId) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (item.getStatus() != ItemStatus.AVAILABLE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "item is not available");
    }
    if (item.getSellerId().equals(buyerId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "cannot order your own listing");
    }
    if (orders.existsByItem_IdAndStatusIn(itemId, ACTIVE)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "item already has an active order");
    }
    item.setStatus(ItemStatus.RESERVED);
    items.save(item);
    return OrderDto.from(orders.save(new Order(item, buyerId, item.getPriceCents())));
  }

  private String validateKey(String key) {
    if (key == null || key.isBlank()) {
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
   *       the {@code @Version} field and is mapped to 409, fail-fast.</li>
   * </ul>
   */
  @Transactional
  public OrderDto pay(Long buyerId, Long orderId) {
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
      payments.capture(order);
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
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
  }
}
