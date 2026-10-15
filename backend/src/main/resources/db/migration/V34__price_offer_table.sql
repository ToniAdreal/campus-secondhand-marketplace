-- V34: price offers table — buyer-proposed prices on AVAILABLE listings
-- (backlog #168). Runs on both MySQL 8 (prod/CI) and H2 in
-- MySQL-compatibility mode (local dev).
--
-- One row per offer. buyer_id / seller_id are plain ids with the FK only
-- on item_id and buyer_id mirroring orders (V4): seller_id is denormalised
-- from the listing at creation time so the seller's received-offers list
-- is a plain indexed read (the listing's seller never changes — there is
-- no transfer-listing flow — so the copy cannot drift).
--
-- "One live PENDING offer per (buyer, item)" is encoded with a generated
-- guard column rather than a plain UNIQUE(item_id, buyer_id, status): a
-- plain unique key would cap history at one row per status, so a buyer
-- whose offer was DECLINED could never offer again. The guard column is
-- the "item:buyer" pair while the offer is PENDING and NULL once it is
-- terminal (ACCEPTED/DECLINED/EXPIRED/WITHDRAWN); UNIQUE on it therefore
-- allows exactly one live offer per pair while offer history stays
-- unbounded — the same construction as V4's active_item_id guard.
-- CONCAT with a ':' separator is plain SQL on both MySQL 8 and H2.
--
-- order_id is set when (and only when) the seller accepts: the order the
-- acceptance created at the agreed price. expires_at is the PENDING
-- offer's deadline (app.offers.pending-ttl, default 48 h from creation);
-- the expiry job flips stale PENDING rows to EXPIRED. The notification
-- offer_id column lands in this same migration (plain ADD COLUMN is
-- portable — see V14's header for why column adds are split one per
-- statement); making notification.order_id nullable is vendor-specific
-- and lives in db/vendor/{vendor}/V35.

CREATE TABLE price_offer (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id BIGINT NOT NULL,
  buyer_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  price_cents BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  order_id BIGINT,
  live_offer_key VARCHAR(64) AS (CASE WHEN status = 'PENDING' THEN CONCAT(item_id, ':', buyer_id) ELSE NULL END),
  expires_at TIMESTAMP NOT NULL,
  version BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_offer_item FOREIGN KEY (item_id) REFERENCES item(id),
  CONSTRAINT fk_offer_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
  CONSTRAINT uq_offer_live_pair UNIQUE (live_offer_key)
);

CREATE INDEX idx_offer_buyer_created ON price_offer(buyer_id, created_at);
CREATE INDEX idx_offer_seller_created ON price_offer(seller_id, created_at);
CREATE INDEX idx_offer_status_expires ON price_offer(status, expires_at);

ALTER TABLE notification ADD COLUMN offer_id BIGINT;
