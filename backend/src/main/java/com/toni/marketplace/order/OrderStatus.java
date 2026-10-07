package com.toni.marketplace.order;

/** Lifecycle of a purchase order. PENDING and PAID are the "active" states — an
 * item may sit in at most one active order at a time (see the
 * uq_order_active_item constraint in Flyway V4). COMPLETED, CANCELLED and
 * REFUNDED are terminal and unbounded per item. REFUNDED needs no migration:
 * V4's generated guard column is {@code CASE WHEN status IN ('PENDING','PAID')
 * THEN item_id ELSE NULL END}, so the new value lands in the terminal (NULL)
 * bucket automatically. */
public enum OrderStatus {
  PENDING,
  PAID,
  COMPLETED,
  CANCELLED,
  REFUNDED
}
