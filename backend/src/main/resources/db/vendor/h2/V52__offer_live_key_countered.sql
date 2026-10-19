-- V52 (H2 vendor variant): COUNTERED joins the offers "live" set —
-- counter-offers (backlog #193). V34's generated guard column keys on
-- PENDING only, so a countered offer would free the (buyer, item) pair
-- and the buyer could open a second negotiation while the seller's
-- counter is still awaiting an answer. The expression is the only
-- change; the column name and the unique constraint keep their V34
-- names. The counter_price_cents column add is portable and lives in
-- V51.
--
-- Vendor-split because dropping a unique constraint differs per
-- database: H2 spells it DROP CONSTRAINT; MySQL spells it DROP INDEX
-- (see the mysql variant of this same version).

ALTER TABLE price_offer DROP CONSTRAINT uq_offer_live_pair;
ALTER TABLE price_offer DROP COLUMN live_offer_key;
ALTER TABLE price_offer ADD COLUMN live_offer_key VARCHAR(64) AS (CASE WHEN status IN ('PENDING','COUNTERED') THEN CONCAT(item_id, ':', buyer_id) ELSE NULL END);
ALTER TABLE price_offer ADD CONSTRAINT uq_offer_live_pair UNIQUE (live_offer_key);
