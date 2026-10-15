package com.toni.marketplace.order;

/** Lifecycle of a purchase order. PENDING, PAID and DISPUTED are the "active"
 * states — an item may sit in at most one active order at a time (see the
 * uq_order_active_item constraint in Flyway V4, widened by the vendor V37
 * migrations). COMPLETED, CANCELLED and REFUNDED are terminal and unbounded
 * per item. REFUNDED needed no migration: V4's generated guard column was
 * {@code CASE WHEN status IN ('PENDING','PAID') THEN item_id ELSE NULL END},
 * so the new value landed in the terminal (NULL) bucket automatically.
 * DISPUTED (backlog #169) is the opposite case — a disputed order must stay
 * active while its ADMIN resolution is pending — so V37 rewrites the guard
 * expression to include it on both vendors. DISPUTED is not terminal: an
 * ADMIN resolution moves the order on to REFUNDED or back to the exact
 * status the dispute froze (PAID or COMPLETED, recorded on the dispute row). */
public enum OrderStatus {
  PENDING,
  PAID,
  COMPLETED,
  CANCELLED,
  REFUNDED,
  DISPUTED
}
