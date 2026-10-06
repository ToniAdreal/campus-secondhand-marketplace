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
   * Application-level counterpart of the {@code uq_order_active_item}
   * constraint: true when the item already sits in an order with one of the
   * given statuses. Checked before creating an order for a friendly 409; the
   * database constraint remains the final arbiter under races.
   */
  boolean existsByItem_IdAndStatusIn(Long itemId, Collection<OrderStatus> statuses);
}
