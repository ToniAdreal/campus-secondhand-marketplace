-- V2: JWT auth foundation — user table + server-side refresh-token store.
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
-- Table is named app_user because USER is a reserved word in MySQL.

CREATE TABLE app_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(60) NOT NULL,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  roles VARCHAR(255) NOT NULL DEFAULT 'USER',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_app_user_username UNIQUE (username),
  CONSTRAINT uq_app_user_email UNIQUE (email)
);

-- Server-side record for each issued refresh token. Refresh tokens are
-- single-use and rotating: each rotation revokes the old row (replaced_by)
-- and inserts a new one. Reuse of a revoked token is treated as theft and
-- revokes the whole token family (see JwtTokenService).
CREATE TABLE refresh_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  token_hash VARCHAR(64) NOT NULL,
  jti VARCHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT FALSE,
  replaced_by VARCHAR(36),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id)
    REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_token_user ON refresh_token(user_id);
