-- V65: seller payout ledger (backlog #221). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- seller_payout: one settlement row per completed order, created in the
-- completion transaction. amount_cents is the order's captured amount
-- minus its cumulative refunds at completion — no platform fee exists
-- in the mock, so none is deducted. References are plain ids (no FK to
-- orders): the ledger must survive the order row it records, mirroring
-- the order_dispute table's discipline, and the unique order_id is the
-- storage-level half of "exactly one settlement row per order".
--
-- seller_payout_adjustment: compensating entries for refunds that land
-- after completion (a dispute resolution — seller refunds require a PAID
-- order and can never target a completed one). A refund never mutates
-- the settlement row; it appends a negative entry here, and the seller's
-- net is settlement plus adjustments.
--
-- Reads are keyed (seller_id, created_at) for the seller list and
-- (status) for the hourly sweep's worklist; adjustments are read by
-- payout_id in one batch query per list page.

CREATE TABLE seller_payout (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  payable_at TIMESTAMP NULL,
  paid_at TIMESTAMP NULL,
  version BIGINT,
  CONSTRAINT uq_seller_payout_order UNIQUE (order_id)
);

CREATE INDEX idx_seller_payout_seller_created ON seller_payout(seller_id, created_at);
CREATE INDEX idx_seller_payout_status ON seller_payout(status);

CREATE TABLE seller_payout_adjustment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  payout_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  amount_cents BIGINT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_seller_payout_adjustment_payout ON seller_payout_adjustment(payout_id);
CREATE INDEX idx_seller_payout_adjustment_seller ON seller_payout_adjustment(seller_id);
