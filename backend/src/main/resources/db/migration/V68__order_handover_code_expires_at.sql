-- V68: handover-code expiry (backlog #234). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- handover_code_expires_at: the instant a minted code stops being
-- valid, stamped by OrderService at pay and at every regeneration as
-- now + app.orders.handover.ttl (default 7 days). The column is
-- deliberately NULLABLE with no backfill: rows minted before #234
-- keep a null expiry and are grandfathered — they never expire by
-- time (see HandoverProperties). Backfilling them to createdAt + ttl
-- or to now + ttl would either instantly kill in-flight handovers
-- that were valid when issued or fabricate an expiry the buyer never
-- agreed to; the grandfathering choice is pinned by
-- OrderHandoverCodeExpiryTest.
--
-- No index: expiry is checked on the single order row the complete
-- endpoint already loads by id, never scanned.

ALTER TABLE orders ADD COLUMN handover_code_expires_at TIMESTAMP NULL;
