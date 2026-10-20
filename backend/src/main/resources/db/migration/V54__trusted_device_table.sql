-- V54: trusted devices for the TOTP challenge (backlog #199).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode
-- (local dev).
--
-- Before this, every login on every device paid the full TOTP
-- challenge forever (#78/#91) — the friction that pushes users to
-- turn 2FA off. A user who completes the challenge may opt in to
-- trusting the device: the server issues a rotating trusted-device
-- token, carried in its own httpOnly cookie (trusted_device). Only
-- the SHA-256 hashes are stored (the refresh_token discipline): the
-- current hash and the immediately previous one. A login presenting
-- the current token still requires the password but skips only the
-- TOTP step, and the token rotates on that use; presenting the
-- rotated-out (previous) token afterwards is treated as a possible
-- theft — that device's trust row is deleted, so neither token
-- works again and the next login pays the full challenge.
-- TTL is application-side (app.auth.trusted-device.ttl, 30 days,
-- sliding: each trusted use extends it). Password change deletes
-- every row of the account; rows are also revocable one-by-one from
-- the sessions surface and in bulk via revoke-all-other-sessions.
CREATE TABLE trusted_device (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  previous_token_hash VARCHAR(64),
  user_agent VARCHAR(512),
  ip_address VARCHAR(64),
  expires_at TIMESTAMP NOT NULL,
  last_used_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_trusted_device_hash UNIQUE (token_hash),
  CONSTRAINT fk_trusted_device_user FOREIGN KEY (user_id)
    REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_trusted_device_user ON trusted_device(user_id);
CREATE INDEX idx_trusted_device_prev_hash ON trusted_device(previous_token_hash);
