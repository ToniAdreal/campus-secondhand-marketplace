-- V10: token_version on app_user — kills pre-change bearer tokens.
--
-- Access tokens are stateless bearer credentials: until now a password
-- change revoked the refresh family (#38) but left already-issued access
-- tokens valid for their full 15 minutes. The new `token_version` column
-- is embedded in every access token's `tver` claim (see JwtTokenService);
-- the authentication filter compares it against the row on every request,
-- and changePassword bumps it, so all pre-change tokens 401 immediately.
--
-- Existing rows get version 0 via the DEFAULT; tokens minted before this
-- migration carry no `tver` claim, which the parser treats as 0 — so they
-- keep working until the account's next password change, which bumps the
-- row to 1 and kills them. That is the only grandfathered cohort, and it
-- is documented in JwtTokenService's class javadoc.
ALTER TABLE app_user
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
