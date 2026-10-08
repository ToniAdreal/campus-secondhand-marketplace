-- V16: category.name_lower — case-insensitive name uniqueness (backlog #70).
--
-- Category names are created by admins with display case ("Books & Study")
-- but the uniqueness contract is case-insensitive (#47's rule): "books &
-- study" must collide with the seeded row. The two supported databases
-- disagree on case sensitivity (MySQL's default collation is
-- case-insensitive, H2 is case-sensitive), so the canonical lowercase form
-- is stored explicitly and carries the unique constraint instead of
-- relying on collation behavior.
--
-- Portability: single ADD COLUMN statements (plain H2 rejects
-- multi-ADD-COLUMN in one ALTER — see V14's header). The NOT NULL column
-- is added with a DEFAULT '' so the backfill UPDATE can run on existing
-- rows first, mirroring V10's pattern.
--
-- BACKFILL CONCERN — read before deploying against real data:
-- If two rows differ only by case this migration FAILS LOUDLY on the
-- unique constraint. That is deliberate: under the new contract they are
-- the same category, and only a human can decide which row survives
-- (rename the loser, then rerun). The V3 seed has no case-variants, so
-- this bites only databases where someone hand-inserted them.
ALTER TABLE category ADD COLUMN name_lower VARCHAR(60) NOT NULL DEFAULT '';
UPDATE category SET name_lower = LOWER(name);
ALTER TABLE category ADD CONSTRAINT uq_category_name_lower UNIQUE (name_lower);
