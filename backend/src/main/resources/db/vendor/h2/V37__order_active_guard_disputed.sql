-- V37 (H2 vendor variant): DISPUTED joins the orders "active" set —
-- order disputes (backlog #169). V4's generated guard column counts an
-- order as active while PENDING/PAID; a disputed order must stay active
-- too (its listing is still RESERVED, or SOLD after a completed handoff)
-- so no second active order can be created for the same listing while
-- an ADMIN resolution is pending. The expression is the only change;
-- the column name and the unique constraint keep their V4 names.
--
-- Vendor-split because dropping a unique constraint differs per
-- database: H2 spells it DROP CONSTRAINT; MySQL spells it DROP INDEX
-- (see the mysql variant of this same version). The dispute table
-- itself is portable and lives in V36.

ALTER TABLE orders DROP CONSTRAINT uq_order_active_item;
ALTER TABLE orders DROP COLUMN active_item_id;
ALTER TABLE orders ADD COLUMN active_item_id BIGINT AS (CASE WHEN status IN ('PENDING','PAID','DISPUTED') THEN item_id ELSE NULL END);
ALTER TABLE orders ADD CONSTRAINT uq_order_active_item UNIQUE (active_item_id);
