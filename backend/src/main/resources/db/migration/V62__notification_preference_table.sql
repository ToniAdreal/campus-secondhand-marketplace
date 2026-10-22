-- V62: per-type notification preferences (backlog #216).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per (user, notification type) the user has explicitly toggled.
-- An ABSENT row means enabled — existing users have no rows at all and
-- their behaviour is unchanged; a row exists only after a settings
-- toggle, so this table stays tiny and the recording seam's lookup is a
-- single-row probe on the unique pair.
--   enabled   the user's choice for that type. Consulted at the single
--             NotificationService record* seam, so no producer can
--             bypass it: a muted type writes no notification row and
--             fires no SSE event.
--   user_id   a real foreign key (accounts are disabled, never
--             deleted, so no cascade is needed); the recording seam
--             probes by (user_id, type) only.
-- Security types (NotificationType.isSecurity — LOGIN_NEW_DEVICE and
-- its peers) are never stored muted: the preference API rejects muting
-- them with 422 and the seam delivers them regardless of any row, so
-- even a hand-written row cannot silence an account-security alert.
CREATE TABLE notification_preference (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_notification_preference_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT uq_notification_preference_user_type UNIQUE (user_id, type)
);

CREATE INDEX idx_notification_preference_user ON notification_preference(user_id);
