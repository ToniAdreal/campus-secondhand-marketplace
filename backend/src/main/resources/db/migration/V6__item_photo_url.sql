-- V6: item.photo_url — optional public path ("/uploads/<uuid>.<ext>") of the
-- listing's primary photo, uploaded via POST /api/items/{id}/photo.
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- Nullable: listings created before image upload existed simply have no photo.
-- The files themselves live outside the database (app.uploads.dir on disk,
-- served by the /uploads/** static resource mapping); the column stores only
-- the public URL path, never the storage location or the original filename.
-- The original client filename is discarded at upload time (see
-- ImageStorageService) so it cannot carry path traversal or executable
-- extensions into the served directory.

ALTER TABLE item ADD COLUMN photo_url VARCHAR(1024) NULL;
