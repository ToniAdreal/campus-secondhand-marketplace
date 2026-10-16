-- V42 (H2 vendor): backfill item.expires_at for rows predating V41
-- (backlog #177) — expires_at = created_at + 30 days, the default
-- app.listings.ttl at migration time. H2 dialect:
-- DATEADD(unit, count, timestamp).

UPDATE item SET expires_at = DATEADD('DAY', 30, created_at) WHERE expires_at IS NULL;
