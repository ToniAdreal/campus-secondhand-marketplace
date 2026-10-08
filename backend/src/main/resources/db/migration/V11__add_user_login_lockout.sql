-- V11: per-account login lockout columns on app_user (backlog #60)
--
-- The per-IP token bucket (#26) does not stop a distributed slow brute-force
-- against a single account (N IPs x 5/min each). These columns track
-- consecutive failed logins per username: after `app.auth.login-lockout.max-attempts`
-- failures the account locks until `locked_until`, and POST /api/auth/login
-- answers 423 with a Retry-After header. A successful login resets the
-- counter. Lockout applies to existing accounts only — unknown identifiers
-- keep getting the identical 401 (no enumeration oracle), so these columns
-- are never consulted for them.
ALTER TABLE app_user
    ADD COLUMN failed_login_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE app_user
    ADD COLUMN locked_until TIMESTAMP NULL;
