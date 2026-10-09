-- V22: append-only audit log for sensitive transitions (backlog #98).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per audited transition: password change, TOTP enable, order
-- complete/refund, ADMIN disable/enable. Deliberately minimal:
--   * no payload column -- the trail records only the ids already in the
--     database, so it stores no new PII (never passwords, TOTP material
--     or request bodies);
--   * no foreign keys -- an audit row must survive deletion of the user
--     or order it describes; referential cleanup would defeat its purpose.

CREATE TABLE audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_user_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_target ON audit_log(target_type, target_id);
CREATE INDEX idx_audit_log_actor ON audit_log(actor_user_id, created_at);
