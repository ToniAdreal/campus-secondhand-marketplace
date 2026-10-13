-- V30: in-app notifications for order lifecycle events (backlog #157).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per real order transition, written inside the transition's own
-- transaction by OrderService (the single write point): pay -> the seller,
-- cancel -> the seller, complete -> the buyer, refund -> the buyer,
-- stale-order expiry -> the buyer. Idempotent replays return before the
-- write, so a replay can never create a duplicate row.
--
-- recipient_id / order_id are plain ids with NO foreign keys, mirroring
-- audit_log (V22): a notification is a derived UI row, and referential
-- cleanup of orders or accounts must never be blocked by it.
-- read_at is NULL while unread; the recipient's mark-read call stamps it
-- once. The (recipient_id, created_at) index backs the paginated list
-- (newest first); (recipient_id, read_at) backs the unread count.

CREATE TABLE notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient_id BIGINT NOT NULL,
  type VARCHAR(24) NOT NULL,
  order_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  read_at TIMESTAMP NULL
);

CREATE INDEX idx_notification_recipient_created ON notification(recipient_id, created_at);
CREATE INDEX idx_notification_recipient_unread ON notification(recipient_id, read_at);
