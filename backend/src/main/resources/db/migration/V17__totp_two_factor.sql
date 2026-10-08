-- V17: TOTP two-factor columns on app_user (backlog #78).
--
-- totp_secret: Base32-encoded RFC 6238 shared secret (160 bits, 32 chars).
-- NULL until the user runs POST /api/auth/2fa/setup. totp_enabled flips to
-- TRUE only after POST /api/auth/2fa/enable verifies a code against the
-- stored secret; POST /api/auth/login for an enabled account answers 202
-- with a short-lived signed challenge instead of a token pair.
--
-- Portability: single ADD COLUMN statements (plain H2 rejects
-- multi-ADD-COLUMN in one ALTER — see V16's header).
--
-- HONEST SCOPE — the secret is stored in the database as-is. Encryption at
-- rest for totp_secret is a declared follow-up; anyone with DB read access
-- can mint valid codes for every 2FA-enabled account.
ALTER TABLE app_user ADD COLUMN totp_secret VARCHAR(64);
ALTER TABLE app_user ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
