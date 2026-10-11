-- V26: email verification for new accounts (backlog #138).
--
-- app_user gains email_verified. The column defaults TRUE so every row
-- that exists at migration time stays verified — this gates NEW signups
-- only; nobody is locked out retroactively. AuthService.register writes
-- FALSE explicitly for new rows while the gate is enabled
-- (app.auth.email-verification.enabled) and issues a verification token
-- instead of a session.
ALTER TABLE app_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;

-- One row per issued verification token. Only the SHA-256 hash of the
-- token is stored (the raw 32-byte token travels exclusively through the
-- mail seam, EmailVerificationMailSender) — a database leak yields no
-- usable tokens, the same rule the password_reset_token table (V20)
-- follows. Tokens are single-use (used_at set on confirm) and expire
-- after app.auth.email-verification.token-ttl (default 24 h); a resend
-- deletes the user's prior unused rows, so at most one live token exists
-- per account.
CREATE TABLE email_verification_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_email_verification_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_email_verification_token_user FOREIGN KEY (user_id)
    REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_email_verification_token_user ON email_verification_token(user_id);
