-- V42 (MySQL vendor): backfill item.expires_at for rows predating V41
-- (backlog #177) — expires_at = created_at + 30 days, the default
-- app.listings.ttl at migration time. MySQL dialect:
-- DATE_ADD(timestamp, INTERVAL n DAY).

UPDATE item SET expires_at = DATE_ADD(created_at, INTERVAL 30 DAY) WHERE expires_at IS NULL;
