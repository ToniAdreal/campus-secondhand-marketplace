-- V12: refresh-token family root columns (backlog #61 — absolute family lifetime)
--
-- A refresh-token family could rotate forever today: each refresh token lives
-- 7 d, but rotation issues a fresh one, so a session that keeps rotating never
-- dies on its own. These two columns pin every row to its family's root:
-- `family_issued_at` is the instant of the original login (populated at login,
-- carried onto each rotated row by JwtTokenService#rotateLive) and `family_jti`
-- identifies the family so a cap expiry kills only that family's chain —
-- unlike theft detection, which still deletes by user (see JwtTokenService).
-- JwtTokenService refuses a refresh past `app.jwt.refresh-max-age` (default
-- 30 d from the family's root) with 401 + family revocation, even when the
-- presented token is unexpired.
--
-- Portability note: this migration runs on MySQL 8 (prod/CI), on H2 in
-- MySQL-compatibility mode (local dev), AND on plain H2 (the @DataJpaTest
-- slice tests, which use an embedded datasource without MODE=MySQL). Plain
-- H2 does not understand MySQL's `MODIFY COLUMN`, so the columns are added
-- NOT NULL with an inert sentinel default instead of the add-nullable /
-- backfill / alter-to-not-null dance — the UPDATE below immediately
-- overwrites the sentinel on every pre-existing row, and every row written
-- after V12 carries real values from JwtTokenService. Rows written before
-- this migration have no recorded family root: they are backfilled from
-- their own created_at/jti (a one-time approximation — for an
-- already-rotated chain it measures from the last rotation rather than the
-- original login, slightly generous, but every family created after V12 gets
-- an exact root).
ALTER TABLE refresh_token
    ADD COLUMN family_issued_at TIMESTAMP NOT NULL DEFAULT '2000-01-01 00:00:00';
ALTER TABLE refresh_token
    ADD COLUMN family_jti VARCHAR(36) NOT NULL DEFAULT '';
UPDATE refresh_token
    SET family_issued_at = created_at,
        family_jti = jti;
