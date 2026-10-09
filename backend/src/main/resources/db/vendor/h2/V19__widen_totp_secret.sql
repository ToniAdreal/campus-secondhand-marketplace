-- V19 (H2): widen app_user.totp_secret 64 -> 255 (backlog #89).
--
-- H2 counterpart of db/vendor/mysql/V19__widen_totp_secret.sql — see that
-- file for the full rationale. H2 (plain and MySQL-compatibility mode)
-- spells a column type change as ALTER COLUMN; MySQL's MODIFY COLUMN
-- does not parse here, hence the vendor split. Existing values are
-- untouched: pre-#89 rows hold plain Base32 and are re-encrypted by the
-- application on the next 2FA enable/authenticate.
ALTER TABLE app_user ALTER COLUMN totp_secret VARCHAR(255);
