-- V37 (MySQL vendor variant): DISPUTED joins the orders "active" set —
-- order disputes (backlog #169). V4's generated guard column counts an
-- order as active while PENDING/PAID; a disputed order must stay active
-- too (its listing is still RESERVED, or SOLD after a completed handoff)
-- so no second active order can be created for the same listing while
-- an ADMIN resolution is pending. The expression is the only change;
-- the column name and the unique constraint keep their V4 names. The
-- bare `AS (expr)` generated-column form mirrors V4 exactly (valid on
-- MySQL 8; H2's MySQL-compat mode accepts its own variant — see the h2
-- variant of this same version).
--
-- Vendor-split because dropping a unique constraint differs per
-- database: MySQL spells it DROP INDEX; H2 spells it DROP CONSTRAINT.

ALTER TABLE orders DROP INDEX uq_order_active_item;
ALTER TABLE orders DROP COLUMN active_item_id;
ALTER TABLE orders ADD COLUMN active_item_id BIGINT AS (CASE WHEN status IN ('PENDING','PAID','DISPUTED') THEN item_id ELSE NULL END);
ALTER TABLE orders ADD CONSTRAINT uq_order_active_item UNIQUE (active_item_id);
