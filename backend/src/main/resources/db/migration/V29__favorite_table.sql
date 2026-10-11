-- V29: favorites table — saved listings (backlog #143).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per (user, listing) save. user_id/item_id are real foreign keys so
-- dangling saves are impossible at the DB level. The item FK cascades on
-- delete: removing a listing (ADMIN delete) removes its saves with it — a
-- favorite of a gone listing could never render anyway. The user FK does
-- not cascade (accounts are disabled, never deleted).
-- The unique pair is what makes PUT save idempotent at the storage layer:
-- a re-save can never create a second row.
-- Reads are keyed (user_id, created_at): the only read path is the caller's
-- own favorites list, newest save first.

CREATE TABLE favorite (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_favorite_item FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE CASCADE,
  CONSTRAINT uq_favorite_user_item UNIQUE (user_id, item_id)
);

CREATE INDEX idx_favorite_user_created ON favorite(user_id, created_at);
