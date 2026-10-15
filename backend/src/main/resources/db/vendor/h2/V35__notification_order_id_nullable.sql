-- V35 (H2 vendor variant): notification.order_id becomes nullable —
-- offer notifications (backlog #168). An OFFER_DECLINED notification has
-- no order (declining never creates one), so the V30 NOT NULL on
-- order_id cannot stand once notifications cover offers as well as
-- orders. Existing order notifications are unaffected: their order_id
-- stays populated.
--
-- Vendor-split because the syntax differs per database: H2 spells this
-- ALTER COLUMN ... SET NULL; MySQL spells it MODIFY (see the mysql
-- variant of this same version). The column add for offer_id is
-- portable and lives in V34.

ALTER TABLE notification ALTER COLUMN order_id SET NULL;
