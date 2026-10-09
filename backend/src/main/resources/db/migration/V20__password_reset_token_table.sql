-- V20: password-reset tokens (backlog #90).
--
-- One row per issued reset token. Only the SHA-256 hash of the token is
-- stored (the raw 32-byte token travels exclusively through the mail seam,
-- PasswordResetMailSender) — a database leak yields no usable tokens, the
-- same rule the refresh_token table follows. Tokens are single-use
-- (used_at set on confirm) and short-lived (30 min, app.auth.password-reset
-- .token-ttl); a new reset request deletes the user's prior unused rows, so
-- at most one live token exists per account.
CREATE TABLE password_reset_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id)
    REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_password_reset_token_user ON password_reset_token(user_id);
