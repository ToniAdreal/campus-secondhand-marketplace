-- V38: ADMIN webhook-event replay bookkeeping (backlog #170).
--
-- replay_count / last_replay_at record ADMIN replay attempts against a
-- ledger row: every attempt bumps the count and stamps the time --
-- including no-op replays of an already-applied event and attempts the
-- freshness re-check refuses -- so the viewer (#156) can show how often
-- an event has been retried and when last. Plain ADD COLUMN statements
-- are portable across MySQL 8 and H2 (see V14's header), one per
-- statement.
ALTER TABLE webhook_event ADD COLUMN replay_count INT NOT NULL DEFAULT 0;
ALTER TABLE webhook_event ADD COLUMN last_replay_at TIMESTAMP NULL;
