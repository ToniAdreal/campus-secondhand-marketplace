-- V36: order disputes table — buyer disputes on PAID/COMPLETED orders
-- (backlog #169). Runs on both MySQL 8 (prod/CI) and H2 in
-- MySQL-compatibility mode (local dev).
--
-- One row per dispute. order_id / buyer_id carry FKs mirroring orders
-- (V4); seller_id is denormalised from the listing at open time (a
-- listing's seller never changes, so the copy cannot drift) to keep the
-- ADMIN queue and the seller reference plain indexed reads.
--
-- previous_status records the order status the dispute froze (PAID or
-- COMPLETED): a resolve-release returns the order to exactly that
-- status, and a resolve-refund needs it to know which listing case
-- applies (RESERVED for a PAID dispute, SOLD for a COMPLETED one).
-- listing_restored is written only by a refund resolution: true when
-- the listing flipped RESERVED -> AVAILABLE, false when it was already
-- SOLD (a completed handoff) and stayed SOLD. It stays NULL on OPEN and
-- RELEASED rows — no refund happened there.
--
-- "One OPEN dispute per order" is encoded with a generated guard
-- column rather than a plain UNIQUE(order_id, status): a plain unique
-- key would cap history at one row per status. The guard column equals
-- order_id while the dispute is OPEN and NULL once resolved; UNIQUE on
-- it therefore allows exactly one live dispute per order while dispute
-- history stays unbounded — the same construction as V4's
-- active_item_id guard and V34's live_offer_key guard.
--
-- Widening the orders guard column so DISPUTED stays an "active"
-- status is vendor-specific (unique-index drop syntax differs) and
-- lives in db/vendor/{vendor}/V37.

CREATE TABLE order_dispute (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  buyer_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(24) NOT NULL,
  previous_status VARCHAR(16) NOT NULL,
  listing_restored BOOLEAN,
  open_order_id BIGINT AS (CASE WHEN status = 'OPEN' THEN order_id ELSE NULL END),
  version BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMP NULL,
  CONSTRAINT fk_dispute_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT fk_dispute_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
  CONSTRAINT uq_dispute_open_order UNIQUE (open_order_id)
);

CREATE INDEX idx_dispute_status_created ON order_dispute(status, created_at);
CREATE INDEX idx_dispute_order ON order_dispute(order_id);
