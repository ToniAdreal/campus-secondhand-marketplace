package com.toni.marketplace.order;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
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

  public OrderService(OrderRepository orders, ItemRepository items,
                     IdempotencyKeyService idempotency, PaymentService payments,
                     OrderCreationService creation) {
    this.orders = orders;
    this.items = items;
    this.idempotency = idempotency;
    this.payments = payments;
    this.creation = creation;
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
    return orders.findByBuyerId(buyerId, pageable).map(OrderDto::from);
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
    return orders.findByItem_SellerId(sellerId, pageable).map(OrderDto::from);
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
   * Cancels an order: PENDING → CANCELLED. Rules:
   * <ul>
   *   <li>Only the order's buyer may cancel; anyone else gets 403. The check
   *       runs before anything else, mirroring {@link #pay(Long, Long)}, so
   *       an order id never leaks what the caller may do with it.</li>
   *   <li>Only PENDING orders can be cancelled. PAID orders → 422 (cancel the
   *       mock payment instead — cancellation after capture is out of
   *       scope); terminal (COMPLETED/CANCELLED) orders → 422, except that
   *       re-cancelling an already-CANCELLED order is idempotent and simply
   *       returns the order.</li>
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
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
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
   *       Honest scope: on a pay race the losing thread still calls
   *       {@link PaymentService#capture} before its version check fails at
   *       flush — the mock is side-effect-free, so nothing double-charges
   *       here. A real PSP client must pass an order-scoped idempotency key
   *       to capture so the loser's pre-flush call is a no-op (declared
   *       follow-up).</li>
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
      return dto;
    } catch (ObjectOptimisticLockingFailureException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "order was updated concurrently, please retry");
    }
  }
}
