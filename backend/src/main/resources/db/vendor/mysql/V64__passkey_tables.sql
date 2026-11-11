-- V64 (MySQL vendor variant): WebAuthn passkey tables (backlog #220).
-- Vendor-split by backlog #372 — see the h2 variant of this same
-- version for the other half. The split exists because the two
-- databases disagree on the credential_id column (below): MySQL 8's
-- utf8mb4 default makes a 1024-char UNIQUE key 4096 bytes, past
-- InnoDB's 3072-byte limit, so the shared V64 failed the compose
-- smoke with "Specified key was too long" (CI run 42842365028, both
-- docker-compose-smoke and backup-restore-drill). Credential ids are
-- base64url, ASCII-only by construction, so this variant pins the
-- column to CHARACTER SET ascii and the key stays 1024 bytes with no
-- data loss. The h2 variant keeps the plain column: plain H2 (the
-- @DataJpaTest embedded database, which runs without MODE=MySQL)
-- rejects CHARACTER SET after a column type. Moving V64 out of
-- db/migration in place is safe: it never applied on any MySQL
-- database (the compose jobs failed at V64 on every run), and local
-- H2 databases are in-memory and recreated per run — no Flyway
-- checksum row exists to contradict.
--
-- passkey_credential: one row per enrolled passkey — the server's half
-- of the credential (the private key never leaves the authenticator).
-- credential_id is the base64url handle, unique across the table; the
-- VARCHAR(1024) (not the WebAuthn-typical 86) is deliberate — credential
-- ids are authenticator-defined and can run long (the webauthn4j test
-- authenticator emits ~270 chars); a 256 column turned real enrolments
-- into 500s (caught by PasskeyCeremonyTest). The public key is stored
-- as its COSE_Key CBOR bytes; aaguid carries the attested authenticator
-- AAGUID (16 bytes; webauthn4j refuses a null AAGUID when the credential
-- record is rebuilt at assertion-verify time — a null here fails the
-- assertion closed as the identical 401, never a 500); sign_count is the
-- last verified signature counter for clone detection (0 when the
-- authenticator does not implement counting, e.g. platform
-- authenticators). Accounts are disabled, never deleted, so the user FK
-- does not cascade — AuthService#closeAccount deletes the rows
-- explicitly, mirroring the TOTP secret clearing.
--
-- passkey_challenge: the single-use server challenges for the two
-- ceremonies (registration, assertion), following the token-table
-- discipline of password_reset_token (#90) / email_verification_token
-- (#138): only the SHA-256 hex of the raw challenge bytes is stored,
-- used_at is stamped on the first verify attempt (success or failure),
-- and at most one live row exists per (user_id, purpose) — issuing new
-- options deletes the prior unused row first.
--
-- Reads are keyed (user_id, created_at) for the settings list and
-- (challenge_hash) unique for the verify path.

CREATE TABLE passkey_credential (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  credential_id VARCHAR(1024) CHARACTER SET ascii NOT NULL,
  public_key_cose BLOB NOT NULL,
  aaguid BINARY(16) NULL,
  sign_count BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_passkey_credential_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT uq_passkey_credential_id UNIQUE (credential_id)
);

CREATE INDEX idx_passkey_credential_user_created ON passkey_credential(user_id, created_at);

CREATE TABLE passkey_challenge (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  challenge_hash VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  used_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_passkey_challenge_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT uq_passkey_challenge_hash UNIQUE (challenge_hash)
);

CREATE INDEX idx_passkey_challenge_user_purpose ON passkey_challenge(user_id, purpose, used_at);
