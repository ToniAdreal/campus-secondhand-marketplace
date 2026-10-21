-- V58: pending email change (backlog #208).
--
-- Before #208, POST /api/auth/email-change flipped app_user.email
-- immediately after the password check (backlog #150): nothing proved
-- the caller controlled the NEW inbox before the switch, so a typo (or
-- a password-only compromise) moved the account's recovery address to
-- a mailbox the owner might not control.
--
-- app_user gains pending_email: the requested new address, stored
-- canonical-lowercase at request time and cleared at confirm. The
-- address itself (app_user.email) does NOT move until the confirm.
ALTER TABLE app_user ADD COLUMN pending_email VARCHAR(255);

-- One row per issued email-change token. Only the SHA-256 hash of the
-- token is stored (the raw 32-byte token travels exclusively through
-- the mail seam, EmailChangeMailSender) — the same rule the
-- email_verification_token (V26) and password_reset_token (V20)
-- tables follow. The token row carries its OWN pending_email copy:
-- the confirm switches to the address the token was issued for, never
-- to whatever a later request may have staged. Tokens are single-use
-- (used_at set on confirm) and expire after the email-verification
-- token TTL (app.auth.email-verification.token-ttl, default 24 h);
-- a fresh request deletes the user's prior unused rows, so at most
-- one live change token exists per account.
CREATE TABLE email_change_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  pending_email VARCHAR(255) NOT NULL,
  token_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_email_change_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_email_change_token_user FOREIGN KEY (user_id)
    REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_email_change_token_user ON email_change_token(user_id);
