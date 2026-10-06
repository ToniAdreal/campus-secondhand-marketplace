-- V4: order table — a buyer's purchase intent for one listing.
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- "Unique for active orders" is encoded with a generated guard column rather
-- than a plain UNIQUE(item_id, status): a plain (item_id, status) unique key
-- would cap history at one row per (item, status) pair, so two CANCELLED orders
-- for the same item would collide — wrong. The guard column equals item_id
-- while the order is open (PENDING/PAID) and NULL once terminal
-- (COMPLETED/CANCELLED); UNIQUE on it therefore allows exactly one open order
-- per item while order history stays unbounded. MySQL treats NULLs in unique
-- indexes as distinct, and so does H2. Bare `AS (expr)` is valid generated-
-- column syntax on both (GENERATED ALWAYS is optional in MySQL 8; H2's
-- MySQL-compat mode accepts the H2-native computed-column form).
--
-- amount_cents snapshots the listing price at order creation: the item price
-- may be edited or the listing deleted later, but the order keeps what the
-- buyer agreed to. The FK to item is intentionally RESTRICT-like — an admin
-- cannot delete a listing while an order references it; delete the order
-- first. Table is named `orders` because ORDER is a reserved word in MySQL.

CREATE TABLE orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id BIGINT NOT NULL,
  buyer_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  amount_cents BIGINT NOT NULL,
  version BIGINT,
  active_item_id BIGINT AS (CASE WHEN status IN ('PENDING','PAID') THEN item_id ELSE NULL END),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_order_item FOREIGN KEY (item_id) REFERENCES item(id),
  CONSTRAINT fk_order_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
  CONSTRAINT uq_order_active_item UNIQUE (active_item_id)
);

CREATE INDEX idx_order_buyer ON orders(buyer_id);
