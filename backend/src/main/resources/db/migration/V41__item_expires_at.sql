-- V41: listing lifetime deadline (backlog #177). Runs on both MySQL 8
-- (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- Listings previously lived forever: a listing posted and forgotten
-- stayed AVAILABLE and searchable indefinitely. expires_at is the
-- deadline after which an AVAILABLE listing is expired — the public
-- reads treat it as gone immediately (lazy-on-read) and the nightly
-- ListingExpiryService flips the stored status to EXPIRED.
--
-- The column is added NULLABLE here so existing rows can be
-- backfilled by the vendor migrations (V42 — the backfill's date
-- arithmetic is not portable SQL): every pre-existing row gets
-- expires_at = created_at + 30 days, the default app.listings.ttl,
-- stated here as the migration comment the backlog requires. Rows
-- written by the application always carry a value; the read paths
-- treat a stray NULL defensively as "no deadline".

ALTER TABLE item ADD COLUMN expires_at TIMESTAMP NULL;

CREATE INDEX idx_item_status_expires ON item(status, expires_at);
