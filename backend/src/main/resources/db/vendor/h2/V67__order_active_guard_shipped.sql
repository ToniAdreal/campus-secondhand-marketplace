-- V67 (H2 vendor variant): SHIPPED joins the orders "active" set —
-- shipped fulfilment path (backlog #222). A shipped order's listing is
-- still RESERVED (it flips to SOLD only when the buyer confirms
-- receipt), so a shipped order must stay active or a second order
-- could be created on an already-sold-and-shipped listing. The
-- expression is the only change; the column name and the unique
-- constraint keep their V4 names.
--
-- Vendor-split because dropping a unique constraint differs per
-- database: H2 spells it DROP CONSTRAINT; MySQL spells it DROP INDEX
-- (see the mysql variant of this same version). The shipment columns
-- themselves are portable and live in V66.

ALTER TABLE orders DROP CONSTRAINT uq_order_active_item;
ALTER TABLE orders DROP COLUMN active_item_id;
ALTER TABLE orders ADD COLUMN active_item_id BIGINT AS (CASE WHEN status IN ('PENDING','PAID','SHIPPED','DISPUTED') THEN item_id ELSE NULL END);
ALTER TABLE orders ADD CONSTRAINT uq_order_active_item UNIQUE (active_item_id);
