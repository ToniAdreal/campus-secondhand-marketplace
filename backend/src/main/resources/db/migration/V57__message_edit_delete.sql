-- V57: message edit + soft delete (backlog #206).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- edited_at is NULL until the sender edits the message inside the edit
-- window (app.messages.edit-window, default 15 minutes from send); the
-- edit stamps it and MessageDto exposes it so the SPA can render an
-- "edited" marker. deleted_at is NULL until the sender soft-deletes:
-- the row is KEPT (thread order and the other party's context survive)
-- and its stored body is replaced by the tombstone text at delete time,
-- so the original wording is gone from storage, not merely hidden.
-- Soft-deleted rows are excluded from the unread count (see
-- MessageRepository) -- a retracted message must not keep a badge lit.
--
-- Portability: plain H2 rejects multi-ADD-COLUMN in one ALTER, so two
-- statements -- the same reason V11, V13, V14, V25, V43 and V44 split theirs.
ALTER TABLE messages ADD COLUMN edited_at TIMESTAMP NULL;
ALTER TABLE messages ADD COLUMN deleted_at TIMESTAMP NULL;
