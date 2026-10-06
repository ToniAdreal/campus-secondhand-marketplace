package com.toni.marketplace.order;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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

  public OrderService(OrderRepository orders, ItemRepository items,
                     IdempotencyKeyService idempotency) {
    this.orders = orders;
    this.items = items;
    this.idempotency = idempotency;
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
   * The actual creation: item must exist, be AVAILABLE, not belong to the
   * buyer, and have no active order. The application-level
   * {@code existsByItem_IdAndStatusIn} check gives a friendly 409; the
   * {@code uq_order_active_item} database constraint stays the final arbiter
   * under races (mapped to 409 by the caller).
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
}
