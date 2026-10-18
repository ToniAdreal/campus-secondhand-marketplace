-- V46: listing view count (backlog #186). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- Sellers could not tell a dead listing from a popular one: the item
-- row carried no view counter and the detail read incremented
-- nothing. view_count is bumped by one atomic UPDATE
-- (SET view_count = view_count + 1) on the detail read, only for an
-- authenticated non-seller, non-ADMIN caller — never by a
-- read-modify-write on the entity, so concurrent readers cannot lose
-- increments to an optimistic-lock race. Existing rows start at 0:
-- no historical view data exists, and inventing a seed count would
-- fabricate a number the seller surface then presents as fact.

ALTER TABLE item ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;
