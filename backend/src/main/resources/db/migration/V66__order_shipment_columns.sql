-- V66: shipped fulfilment path (backlog #222). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- carrier / tracking_number: the seller-entered shipment record, set by
-- POST /api/orders/{id}/ship in the same transaction as the PAID ->
-- SHIPPED transition. Both are free text — no carrier integration
-- exists, so nothing here is a verified tracking status. The widths
-- mirror the ShipOrderRequest jakarta caps (64 / 128).
--
-- shipped_at: the ship instant, stamped from the service Clock in the
-- same transaction (the dispute window is measured from updatedAt, so
-- this column is a record, not a window input).
--
-- The active-order guard expression (V4, widened by vendor V37 for
-- DISPUTED) is NOT touched here: dropping a unique constraint differs
-- per database, so the SHIPPED widening lives in the vendor V67
-- migrations, mirroring the V37/V52 split discipline.

ALTER TABLE orders ADD COLUMN carrier VARCHAR(64) NULL;
ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(128) NULL;
ALTER TABLE orders ADD COLUMN shipped_at TIMESTAMP NULL;
