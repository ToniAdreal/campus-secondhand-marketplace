-- V56: listing price history (backlog #201). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per price a listing has carried: the creation price, plus one
-- row for every PATCH edit that actually changes price_cents (a no-op
-- edit appends nothing; status flips and photo changes never append —
-- both rules live in ItemService, the only writer). item_id is a real
-- foreign key with ON DELETE CASCADE, mirroring recently_viewed (V53):
-- price history has no meaning without its listing, so an ADMIN delete
-- takes the history with it.
--
-- Backfill: listings created before this migration get exactly one seed
-- row — their CURRENT price stamped at their creation time — the V49
-- pattern. That is the only honest reconstruction available: the price
-- a pre-V56 listing carried at any earlier moment was never recorded
-- (#189's favorite snapshots aside, which are per-user and not a
-- listing history), and inventing intermediate points would fabricate
-- history the README row explicitly disclaims.
--
-- Reads are keyed (item_id, changed_at): the only read path is one
-- listing's history, oldest first.

CREATE TABLE price_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id BIGINT NOT NULL,
  price_cents BIGINT NOT NULL,
  changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_price_history_item FOREIGN KEY (item_id)
    REFERENCES item(id) ON DELETE CASCADE
);

CREATE INDEX idx_price_history_item_changed ON price_history(item_id, changed_at);

INSERT INTO price_history (item_id, price_cents, changed_at)
SELECT id, price_cents, created_at FROM item;
