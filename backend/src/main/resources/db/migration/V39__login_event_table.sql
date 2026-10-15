-- V39: per-user login history (backlog #171).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per login OUTCOME: every password-stage verdict of
-- AuthService.loginAttempt and every second-factor verdict of
-- AuthService.authenticateTotp appends exactly one row through
-- LoginHistoryRecorder (REQUIRES_NEW), so a row survives the 401/403/423
-- its own attempt answers — the history is evidence about failed logins
-- above all, and recording inside the login transaction would let that
-- transaction's rollback erase precisely the rows that matter.
--
-- user_id is a plain id with NO foreign key, mirroring listing_report
-- (V31): attempts against unknown identifiers are recorded with a NULL
-- user_id (there is no account to reference), and an account later
-- anonymized to its deleted-<id> tombstone (backlog #158) must neither
-- block on nor silently erase the sign-in evidence recorded against it.
-- identifier is the attempted identifier exactly as typed (username or
-- email, before canonicalization), so an unknown-identifier row still
-- shows what was tried; for second-factor rows it is the account's
-- username (the challenge carries no typed identifier).
--
-- Reads are keyed (user_id, created_at): the only read path is the
-- caller's own history, newest first (GET /api/auth/login-history) —
-- rows with a NULL user_id are never returned by it. The (created_at)
-- index backs the DataRetentionService purge, which deletes by
-- created_at only, never by outcome or user.
CREATE TABLE login_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NULL,
  identifier VARCHAR(320) NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  ip_address VARCHAR(45) NULL,
  user_agent VARCHAR(512) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_login_event_user_created ON login_event(user_id, created_at);
CREATE INDEX idx_login_event_created ON login_event(created_at);
