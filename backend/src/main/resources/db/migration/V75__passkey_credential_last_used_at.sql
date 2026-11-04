-- V75: last-used timestamp on passkey credentials (backlog #329). Runs on both
-- MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- passkey_credential gains a nullable last_used_at, stamped only when a
-- passkey assertion succeeds (see AuthService#authenticatePasskey, next to
-- the signature-counter advance it already persists). A failed assertion
-- leaves it untouched, so the column answers "when did this key last
-- complete a login" and never "when was it last tried". The column is
-- added with an implicit NULL default and NO backfill UPDATE: rows
-- enrolled before #329 keep a NULL value, and the settings surface
-- renders those rows with the "not recorded yet" label — a NULL here
-- means "no successful use recorded", not a missing row, and no
-- historical row is rewritten to invent a use it may or may not have
-- had before tracking existed.

ALTER TABLE passkey_credential ADD COLUMN last_used_at TIMESTAMP NULL;
