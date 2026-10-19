-- V53: recently-viewed listings (backlog #195). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per (user, listing) detail view. user_id/item_id are real
-- foreign keys so a dangling trail row is impossible at the DB level.
-- The item FK cascades on delete: removing a listing (ADMIN delete)
-- removes its trail rows with it — a view of a gone listing could
-- never render anyway (the read projection would omit it regardless).
-- The user FK does not cascade (accounts are disabled, never deleted).
-- The unique pair is what makes the detail read's record step an
-- upsert: a repeat view updates viewed_at on the existing row instead
-- of creating a second one, which is also what moves the listing to
-- the front of the owner's trail.
-- Reads are keyed (user_id, viewed_at): the only read path is the
-- caller's own recently-viewed list, most-recent first. Retention is
-- judged by viewed_at too (the nightly purge in DataRetentionService):
-- a listing the owner keeps revisiting stays fresh, a trail nobody
-- has touched ages out.

CREATE TABLE recently_viewed (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  viewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_recently_viewed_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_recently_viewed_item FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE CASCADE,
  CONSTRAINT uq_recently_viewed_user_item UNIQUE (user_id, item_id)
);

CREATE INDEX idx_recently_viewed_user_viewed ON recently_viewed(user_id, viewed_at);
