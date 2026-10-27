-- V71: friendly name on passkey credentials (backlog #241). Runs on both
-- MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- passkey_credential gains a nullable display name (max 100 chars, the
-- same bound the rename/enrolment validation enforces). The column is
-- added with an implicit NULL default and NO backfill UPDATE: rows
-- enrolled before #241 keep a NULL name, and the settings surface
-- renders those rows with the pre-#241 date label ("Passkey added
-- <date>") — a blank row is never shown, and no historical row is
-- rewritten to invent a name it never had. A name supplied at
-- enrolment (or later via PATCH) is stored trimmed; a blank rename
-- clears the column back to NULL, restoring the date fallback.

ALTER TABLE passkey_credential ADD COLUMN name VARCHAR(100) NULL;
