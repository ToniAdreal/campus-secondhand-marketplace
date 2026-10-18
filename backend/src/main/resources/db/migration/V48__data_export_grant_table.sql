-- V48: single-use step-up grant for the account data export
-- (backlog #188). Runs on both MySQL 8 (prod/CI) and H2 in
-- MySQL-compatibility mode (local dev).
--
-- The account data export (#172) is the heaviest sensitive read in
-- the app; #188 puts it behind a fresh proof (current password, plus
-- a TOTP/recovery code when 2FA is enabled). POST
-- /api/users/me/export/confirm verifies the proof and mints one row
-- here: 32 random bytes, only the SHA-256 hash stored (the #90/#138
-- token discipline — the raw grant travels only in the confirm
-- response and the X-Export-Grant header of the one download it
-- authorizes). Single-use: a successful export stamps used_at, and a
-- replayed, expired or unknown grant is the identical 401. A fresh
-- confirm deletes the user's prior rows, so at most one live grant
-- exists per account.
-- TTL is application-side (app.auth.data-export.grant-ttl, 5 min).

CREATE TABLE data_export_grant (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_data_export_grant_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT uq_data_export_grant_hash UNIQUE (token_hash)
);

CREATE INDEX idx_data_export_grant_user ON data_export_grant(user_id);
