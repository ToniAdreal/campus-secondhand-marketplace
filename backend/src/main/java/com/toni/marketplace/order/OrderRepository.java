package com.toni.marketplace.order;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

  List<Order> findByBuyerId(Long buyerId);

  /** Paginated variant of {@code findByBuyerId} for the buyer's order list. */
  Page<Order> findByBuyerId(Long buyerId, Pageable pageable);

  /**
   * Paginated orders placed on one seller's listings — the seller side of
   * the marketplace read model. Spring Data derives the join on
   * {@code order.item.sellerId}; the page sort is applied by the caller
   * (newest first by default).
   */
  Page<Order> findByItem_SellerId(Long sellerId, Pageable pageable);

  /**
   * Application-level counterpart of the {@code uq_order_active_item}
   * constraint: true when the item already sits in an order with one of the
   * given statuses. Checked before creating an order for a friendly 409; the
   * database constraint remains the final arbiter under races.
   */
  boolean existsByItem_IdAndStatusIn(Long itemId, Collection<OrderStatus> statuses);
}
