package com.toni.marketplace.order;

/** Lifecycle of a purchase order. PENDING and PAID are the "active" states — an
 * item may sit in at most one active order at a time (see the
 * uq_order_active_item constraint in Flyway V4). COMPLETED and CANCELLED are
 * terminal and unbounded per item. */
public enum OrderStatus {
  PENDING,
  PAID,
  COMPLETED,
  CANCELLED
}
