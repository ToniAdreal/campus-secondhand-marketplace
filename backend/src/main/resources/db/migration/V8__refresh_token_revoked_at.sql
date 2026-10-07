-- V8: record when a refresh token row was revoked.
-- Backlog #39 (refresh-rotation grace window): a replayed just-rotated token
-- is accepted once more inside a short grace window, keyed off the
-- replaced_by chain. The window is measured from revoked_at, so rotation must
-- timestamp the revocation.
--
-- Rows revoked before this migration have NULL revoked_at: they never receive
-- a grace grant (a replay of them takes the theft path and kills the family).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).

ALTER TABLE refresh_token
  ADD COLUMN revoked_at TIMESTAMP NULL;
