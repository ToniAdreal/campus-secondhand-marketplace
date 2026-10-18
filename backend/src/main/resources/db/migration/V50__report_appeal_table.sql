-- V50: report appeals — seller recourse for ACTIONED listing reports
-- (backlog #190). Runs on both MySQL 8 (prod/CI) and H2 in
-- MySQL-compatibility mode (local dev) — the CREATE TABLE, the
-- generated column and the ADD COLUMN below are portable across both.
--
-- One row per appeal of one ACTIONED report. report_id / seller_id are
-- plain ids with NO foreign keys, mirroring listing_report (V31)
-- rather than order_dispute (V36): an appeal is moderation evidence
-- tied to a report, and deleting a listing or disabling an account
-- must never be blocked by — or silently erase — the appeals filed
-- against its reports. (Disputes carry FKs because they sit inside
-- the order lifecycle; reports and appeals sit outside it.)
--
-- status is OPEN -> UPHELD / REJECTED, both terminal. listing_restored
-- is written only by an uphold resolution: true when the listing was
-- still WITHDRAWN and flipped back to AVAILABLE, false when it had
-- moved on (RESERVED / SOLD / EXPIRED) and was left untouched. It
-- stays NULL on OPEN and REJECTED rows — no restoration was attempted
-- there — mirroring order_dispute.listing_restored (V36).
--
-- "One OPEN appeal per report" is encoded with a generated guard
-- column rather than a plain UNIQUE(report_id, status): a plain
-- unique key would cap history at one row per status. The guard
-- column equals report_id while the appeal is OPEN and NULL once
-- resolved; UNIQUE on it therefore allows exactly one live appeal per
-- report while appeal history stays unbounded — the same construction
-- as V36's open_order_id and V4's active_item_id guards. (The service
-- additionally refuses a second appeal after any resolution — one
-- appeal per report, ever — so in practice at most one row per report
-- exists; the guard is the database-level arbiter for racing opens.)
--
-- notification gains the appeal payload in the same migration (the
-- V45/V49 pattern): appeal_id names the appeal a REPORT_APPEAL_UPHELD
-- / REPORT_APPEAL_REJECTED row is about, so the SPA can link to the
-- seller's appeal state instead of misusing the order_id column for a
-- non-order id. Plain value, no foreign key, mirroring the rest of
-- the notification table (V30).

CREATE TABLE report_appeal (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  report_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL,
  listing_restored BOOLEAN,
  live_report_id BIGINT AS (CASE WHEN status = 'OPEN' THEN report_id ELSE NULL END),
  version BIGINT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMP NULL,
  CONSTRAINT uq_report_appeal_live_report UNIQUE (live_report_id)
);

CREATE INDEX idx_report_appeal_status_created ON report_appeal(status, created_at);
CREATE INDEX idx_report_appeal_report ON report_appeal(report_id);
CREATE INDEX idx_report_appeal_seller_created ON report_appeal(seller_id, created_at);

ALTER TABLE notification ADD COLUMN appeal_id BIGINT;
