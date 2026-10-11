-- V27: password history (backlog #139).
--
-- One row per OUTGOING password hash: every successful password change
-- (AuthService.changePassword) or reset confirm
-- (PasswordResetService.confirmReset) appends the hash being replaced,
-- inside the same transaction as the replacement itself. A new password
-- is rejected when it matches the current hash or any of the last
-- app.auth.password-history-size (default 5) stored hashes, so A→B→A
-- cycling no longer works. Only BCrypt hashes are stored — never a raw
-- password. Rows are never updated; the check reads the newest N by id.
CREATE TABLE password_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_password_history_user FOREIGN KEY (user_id)
    REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_password_history_user ON password_history(user_id);
