-- V21: TOTP recovery codes (backlog #91).
--
-- One row per issued recovery code. #78's 2FA had no fallback: losing the
-- authenticator device locked the account permanently (the #90 password
-- reset deliberately does not bypass 2FA). Enabling 2FA now also issues
-- 10 one-time recovery codes, shown exactly once in the enable response.
-- Only the SHA-256 hash of the normalized code is stored (the same rule
-- the refresh_token and password_reset_token tables follow) — a database
-- leak yields no usable codes. A code is consumed by stamping used_at;
-- replaying it gets the identical 401 as a wrong TOTP code. Re-running
-- setup/enable deletes the user's rows and issues a fresh set, and the
-- FK cascade removes the rows when the account itself is deleted.
CREATE TABLE totp_recovery_code (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  code_hash VARCHAR(64) NOT NULL,
  used_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_totp_recovery_code_hash UNIQUE (code_hash),
  CONSTRAINT fk_totp_recovery_code_user FOREIGN KEY (user_id)
    REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_totp_recovery_code_user ON totp_recovery_code(user_id);
