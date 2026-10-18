-- V49: price-drop notifications for favorited listings (backlog #189).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode
-- (local dev) — the ADD COLUMNs and the correlated-subquery backfill
-- below are portable across both.
--
-- favorite.price_snapshot_cents is the listing's price at the moment
-- the user saved it. Existing rows are backfilled from the item's
-- CURRENT price at migration time (stated here as the backlog
-- requires): a pre-existing save baselines at today's price, so only
-- drops after this migration can ever notify. The column stays
-- nullable for defensive reads — application writes always carry a
-- value, and a stray NULL is treated as "no baseline yet" (the first
-- later drop baselines it silently instead of notifying).
--
-- notification gains the FAVORITE_PRICE_DROP payload in the same
-- migration (the V45 pattern): item_id names the listing the SPA
-- links to, and old_price_cents / new_price_cents are the favoriter's
-- snapshot before the drop and the listing's new price — the copy
-- states both. All three are plain values with no foreign keys,
-- mirroring the rest of the notification table (V30).

ALTER TABLE favorite ADD COLUMN price_snapshot_cents BIGINT;

UPDATE favorite
SET price_snapshot_cents =
    (SELECT price_cents FROM item WHERE item.id = favorite.item_id);

ALTER TABLE notification ADD COLUMN item_id BIGINT;

ALTER TABLE notification ADD COLUMN old_price_cents BIGINT;

ALTER TABLE notification ADD COLUMN new_price_cents BIGINT;
