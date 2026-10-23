-- V63: following a seller (backlog #217). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per (follower, seller) pair: the seller is the user whose
-- listings the follower wants to hear about. Both ids are real foreign
-- keys to app_user, mirroring user_block (V47): accounts are disabled,
-- never deleted, so neither FK cascades. The unique pair is the
-- storage-level half of the follow endpoint's idempotency — a repeat
-- follow can never create a second row, and a concurrent double-follow
-- collapses onto it.
--
-- Reads are keyed (follower_id, created_at): the only list read is the
-- follower's own following list, newest first; the publish fan-out is
-- an exact-set read keyed on the unique index via (seller_id).

CREATE TABLE seller_follow (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  follower_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_seller_follow_follower FOREIGN KEY (follower_id) REFERENCES app_user(id),
  CONSTRAINT fk_seller_follow_seller FOREIGN KEY (seller_id) REFERENCES app_user(id),
  CONSTRAINT uq_seller_follow_pair UNIQUE (follower_id, seller_id)
);

CREATE INDEX idx_seller_follow_follower_created ON seller_follow(follower_id, created_at);
