package com.toni.marketplace.order;

import com.toni.marketplace.item.Item;
import com.toni.marketplace.item.ItemRepository;
import com.toni.marketplace.item.ItemStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The transactional half of order creation. This is a separate bean from
 * {@link OrderService} on purpose: {@code OrderService.createOrder} performs
 * the idempotency-key lifecycle in its own {@code REQUIRES_NEW} transactions
 * and must not share a transaction with the order write, so the creation
 * work lives here behind a real Spring proxy.
 *
 * <p>Previously this logic was a {@code @Transactional} method on
 * {@code OrderService} called via self-invocation, which bypassed the proxy
 * and silently ran with no wrapping transaction: the listing flip and the
 * order insert committed in separate repository transactions, so a crash
 * between the two could leave an order without a RESERVED flip. Now the
 * flip and the insert commit atomically — if the insert fails after the
 * flip (constraint violation, version conflict, …), the whole transaction
 * rolls back and the listing stays AVAILABLE.
 */
@Service
public class OrderCreationService {

  private static final List<OrderStatus> ACTIVE =
      List.of(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.SHIPPED,
          OrderStatus.DISPUTED);

  private final OrderRepository orders;
  private final ItemRepository items;

  public OrderCreationService(OrderRepository orders, ItemRepository items) {
    this.orders = orders;
    this.items = items;
  }

  /**
   * Creates the order inside a single transaction. Guards:
   * <ul>
   *   <li>unknown item → 404; item not AVAILABLE → 409; buying your own
   *       listing → 422.</li>
   *   <li>The application-level {@code existsByItem_IdAndStatusIn} check
   *       gives a friendly 409; the {@code uq_order_active_item} database
   *       constraint stays the final arbiter under races (surfaces here as a
   *       constraint violation, mapped to 409 by the caller).</li>
   * </ul>
   * The listing flips AVAILABLE → RESERVED as part of creation, so the list
   * view (which projects {@code Item.status}) stops offering an item the
   * moment someone orders it. The flip goes through
   * {@link ItemRepository#save} (merge): the merge bumps
   * {@code Item.@Version}, so two concurrent creations racing on one listing
   * fail fast on the stale version instead of both inserting.
   */
  @Transactional
  public OrderDto create(Long buyerId, Long itemId) {
    return create(buyerId, itemId, null);
  }

  /**
   * {@link #create(Long, Long)} at an explicitly agreed price instead of
   * the listing's current price (backlog #168): the price-offer accept
   * path creates the order at the OFFERED price the seller accepted, so
   * the order's {@code amountCents} snapshot is the agreed figure, never
   * the asking price. A null {@code amountCents} keeps the buy-now
   * behaviour (snapshot the listing price). Every guard — unknown item
   * 404, not-AVAILABLE 409, own-listing 422, active-order 409 — and the
   * atomic RESERVED flip are shared verbatim: there is exactly one
   * order-creation dialect in this codebase, offers included. Called
   * from {@code OfferService.accept}'s transaction, this method joins it
   * (REQUIRED propagation), so the offer flip, the listing flip and the
   * order insert commit — or roll back — together.
   */
  @Transactional
  public OrderDto create(Long buyerId, Long itemId, Long amountCents) {
    Item item = items.findById(itemId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
    if (item.getStatus() != ItemStatus.AVAILABLE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "item is not available");
    }
    // Listing expiry (backlog #177): an AVAILABLE listing past its
    // lifetime deadline is expired even before the nightly job flips
    // its stored status — the same lazy-on-read definition the browse
    // and detail reads use, so a direct order POST cannot buy a
    // listing every read path already treats as gone.
    if (item.isExpiredAt(java.time.Instant.now())) {
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
    OrderDto created = OrderDto.from(orders.save(
        new Order(item, buyerId, amountCents != null ? amountCents : item.getPriceCents())));
    // Flush deliberately inside the transaction (not at commit), mirroring
    // pay()/cancel(): a constraint violation on the insert (e.g. the
    // uq_order_active_item race) or a stale-version flip surfaces here and
    // rolls the flip + insert back together. Without the flush the failure
    // would surface at commit — still atomic, but outside this method's
    // documented failure contract.
    orders.flush();
    return created;
  }
}
