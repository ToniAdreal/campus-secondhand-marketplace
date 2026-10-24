package com.toni.marketplace.order;

/** Lifecycle of a purchase order. PENDING, PAID, SHIPPED and DISPUTED are the
 * "active" states — an item may sit in at most one active order at a time
 * (see the uq_order_active_item constraint in Flyway V4, widened by the
 * vendor V37 migrations and again by the vendor V67 migrations for
 * SHIPPED). COMPLETED, CANCELLED and REFUNDED are terminal and unbounded
 * per item. REFUNDED needed no migration: V4's generated guard column was
 * {@code CASE WHEN status IN ('PENDING','PAID') THEN item_id ELSE NULL END},
 * so the new value landed in the terminal (NULL) bucket automatically.
 * DISPUTED (backlog #169) is the opposite case — a disputed order must stay
 * active while its ADMIN resolution is pending — so V37 rewrites the guard
 * expression to include it on both vendors. DISPUTED is not terminal: an
 * ADMIN resolution moves the order on to REFUNDED or back to the exact
 * status the dispute froze (PAID, SHIPPED or COMPLETED, recorded on the
 * dispute row). SHIPPED (backlog #222) is the mailed-sale in-transit
 * state: PAID → SHIPPED (seller records carrier + tracking number) →
 * COMPLETED (buyer confirms receipt, the same RESERVED → SOLD flip as the
 * in-person handover path). A shipped order's listing stays RESERVED, so
 * SHIPPED must stay in the active set or a second order could be created
 * on an already-sold-and-shipped listing. */
public enum OrderStatus {
  PENDING,
  PAID,
  SHIPPED,
  COMPLETED,
  CANCELLED,
  REFUNDED,
  DISPUTED
}
