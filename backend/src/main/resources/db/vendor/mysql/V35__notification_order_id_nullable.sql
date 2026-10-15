-- V35 (MySQL vendor variant): notification.order_id becomes nullable —
-- offer notifications (backlog #168). An OFFER_DECLINED notification has
-- no order (declining never creates one), so the V30 NOT NULL on
-- order_id cannot stand once notifications cover offers as well as
-- orders. Existing order notifications are unaffected: their order_id
-- stays populated.
--
-- Vendor-split because the syntax differs per database: MySQL spells
-- this MODIFY; H2 spells it ALTER COLUMN ... SET NULL (see the h2
-- variant of this same version). The column add for offer_id is
-- portable and lives in V34.

ALTER TABLE notification MODIFY order_id BIGINT NULL;
