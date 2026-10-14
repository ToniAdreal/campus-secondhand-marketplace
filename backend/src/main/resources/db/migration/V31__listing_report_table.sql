-- V31: listing reports + ADMIN moderation queue (backlog #160).
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per report of one listing by one user: reporter, item, reason
-- (SPAM/SCAM/PROHIBITED/OTHER), an optional free-text note, and the
-- moderation status (OPEN -> DISMISSED / ACTIONED; both terminal).
--
-- reporter_id / item_id are plain ids with NO foreign keys, mirroring
-- audit_log (V22) and notification (V30): a report is moderation evidence,
-- and deleting a listing (ADMIN delete) or disabling an account must never
-- be blocked by — or silently erase — the reports filed against it.
--
-- "One OPEN report per (reporter, item)" is enforced in ListingReportService
-- (a repeat returns the existing OPEN row); it cannot be a unique constraint
-- here because the invariant is per-status — after a report is DISMISSED the
-- same reporter may legitimately report the same listing again, and a plain
-- (reporter_id, item_id, status) unique pair would then forbid the second
-- DISMISSED row. The (reporter_id, item_id, status) index backs the dedupe
-- lookup; (status, created_at) backs the ADMIN queue (newest first,
-- optionally filtered by status).

CREATE TABLE listing_report (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reporter_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  reason VARCHAR(16) NOT NULL,
  note VARCHAR(1000) NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_listing_report_status_created ON listing_report(status, created_at);
CREATE INDEX idx_listing_report_reporter_item ON listing_report(reporter_id, item_id, status);
