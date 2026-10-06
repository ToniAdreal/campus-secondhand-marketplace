-- V7: messages table — offline buyer↔seller messaging, v1.
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per message. sender_id/receiver_id/item_id are real foreign keys to
-- app_user and item so dangling references are impossible at the DB level;
-- the API additionally checks business rules (see MessageService).
-- The payload column is message_body, not `body`: BODY is a keyword in H2's
-- parser, so an unquoted body column would break local schema validation.
-- Conversation reads are keyed (item_id, created_at): the per-listing thread
-- is the only read path (GET /api/messages?itemId=), always chronological.
-- This is v1: a plain thread store. Delivery receipts, read state, and
-- realtime push are deliberately out of scope (honest scope note).

CREATE TABLE messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id BIGINT NOT NULL,
  sender_id BIGINT NOT NULL,
  receiver_id BIGINT NOT NULL,
  message_body VARCHAR(2000) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_msg_item FOREIGN KEY (item_id) REFERENCES item(id),
  CONSTRAINT fk_msg_sender FOREIGN KEY (sender_id) REFERENCES app_user(id),
  CONSTRAINT fk_msg_receiver FOREIGN KEY (receiver_id) REFERENCES app_user(id)
);

CREATE INDEX idx_msg_item_thread ON messages(item_id, created_at);
