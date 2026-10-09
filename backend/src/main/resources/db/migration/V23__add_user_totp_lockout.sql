-- V23: per-account TOTP authenticate lockout columns on app_user (backlog #101)
--
-- The 2FA challenge is a 5-minute Bearer <redacted> for a 6-digit code, so
-- POST /api/auth/2fa/authenticate is an online-guessing surface. These
-- columns track consecutive failed exchanges per account: after
-- `app.auth.totp-lockout.max-attempts` failures the exchange locks until
-- `totp_locked_until` and answers 423 with a Retry-After header. A
-- successful exchange resets the counter.
--
-- Deliberately SEPARATE columns from the #60 password lockout
-- (failed_login_attempts / locked_until): a challenge holder must not be
-- able to lock the account owner out of password login entirely. While
-- TOTP-locked, login still verifies the password and issues a challenge;
-- only the exchange is refused. Neither lock reads or writes the other.
ALTER TABLE app_user
    ADD COLUMN failed_totp_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE app_user
    ADD COLUMN totp_locked_until TIMESTAMP NULL;
