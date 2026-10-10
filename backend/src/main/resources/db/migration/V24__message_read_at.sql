-- V24: message read state (backlog #106).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- read_at is NULL until the message's receiver opens the listing thread
-- (GET /api/messages?itemId=), which stamps every unread row addressed to
-- the caller on that listing in the same transaction. A sender's own view
-- never stamps their own rows -- read state belongs to the receiver.
-- The (receiver_id, read_at) index backs GET /api/messages/unread-count:
-- count rows where receiver_id = caller AND read_at IS NULL.
ALTER TABLE messages
    ADD COLUMN read_at TIMESTAMP NULL;

CREATE INDEX idx_msg_receiver_unread ON messages(receiver_id, read_at);
