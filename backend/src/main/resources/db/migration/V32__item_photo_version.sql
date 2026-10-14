-- V32: item_photo optimistic-lock version (backlog #162).
--
-- Gallery reorder (PUT /api/items/{id}/photos/order) rewrites the
-- position column of every row in a listing's gallery in one
-- transaction. The @Version column makes a concurrent edit of the
-- same gallery fail fast at flush (mapped to 409 by
-- GlobalExceptionHandler, backlog #147) instead of silently
-- overwriting the winning reorder. Existing rows start at 0, the
-- same value Hibernate assigns to freshly persisted rows.
--
-- Portability: plain H2 and MySQL 8 both accept this ANSI DDL,
-- mirroring the item table's version column from V1.

ALTER TABLE item_photo ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
