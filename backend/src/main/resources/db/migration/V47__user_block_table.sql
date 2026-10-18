-- V47: user-to-user blocking (backlog #187). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per (blocker, blocked) pair. Both ids are real foreign keys
-- to app_user so a block of a deleted/disabled-era phantom id is
-- impossible at the DB level; accounts are disabled, never deleted,
-- so neither FK cascades. The unique pair is the storage-level half
-- of the block endpoint's idempotency: a repeat block can never
-- create a second row.
-- Reads are keyed (blocker_id, created_at): the only list read is
-- the blocker's own blocked-users list, newest block first; the
-- enforcement reads are exact-pair existence checks on the unique
-- index.

CREATE TABLE user_block (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  blocker_id BIGINT NOT NULL,
  blocked_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_user_block_blocker FOREIGN KEY (blocker_id) REFERENCES app_user(id),
  CONSTRAINT fk_user_block_blocked FOREIGN KEY (blocked_id) REFERENCES app_user(id),
  CONSTRAINT uq_user_block_pair UNIQUE (blocker_id, blocked_id)
);

CREATE INDEX idx_user_block_blocker_created ON user_block(blocker_id, created_at);
