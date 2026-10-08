-- V15: item_photo — multiple photos per listing (backlog #69).
--
-- Until now a listing carried a single photo_url (Flyway V6, set by
-- POST /api/items/{id}/photo). The gallery table stores an ordered photo
-- list per listing: position is the display order, and the row with the
-- lowest position is the listing's primary photo (the one a client should
-- render first). The listing row keeps its photo_url column untouched —
-- it remains the legacy single-photo field; new uploads go through
-- POST /api/items/{id}/photos into this table.
--
-- Files still live outside the database (app.uploads.dir on disk, served
-- by the /uploads/** static resource mapping); the url column stores only
-- the public path ("/uploads/<uuid>.<ext>"), never the storage location.
--
-- Portability: plain H2 (local @DataJpaTest slices) and MySQL 8
-- (compose/CI) both accept this ANSI DDL; AUTO_INCREMENT matches the
-- existing V1 item table. ON DELETE CASCADE keeps orphaned rows from
-- surviving a listing delete; the files themselves are removed
-- best-effort by ItemService (see #40's rule: a disk hiccup must never
-- roll back a row delete).

CREATE TABLE item_photo (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id BIGINT NOT NULL,
  url VARCHAR(1024) NOT NULL,
  position INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_item_photo_item FOREIGN KEY (item_id) REFERENCES item(id) ON DELETE CASCADE
);

CREATE INDEX idx_item_photo_item_position ON item_photo(item_id, position);
