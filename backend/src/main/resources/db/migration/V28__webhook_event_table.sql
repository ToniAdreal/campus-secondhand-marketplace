-- V28: mock-PSP webhook event ledger (backlog #140).
--
-- One row per ACCEPTED webhook delivery (signature verified), keyed on
-- the PSP event id: a replayed delivery finds its row already present
-- and is answered 200 without re-applying anything. The row records
-- only what reconciliation needs -- the event id, its type, the PSP
-- reference it carried (capture or refund id, backlog #114) and whether
-- it matched an order already in the matching state. A webhook never
-- moves money state by itself, so there is deliberately no order FK
-- and no status column to mutate here: this table is an audit /
-- idempotency ledger, not a second source of truth for order state.
CREATE TABLE webhook_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  psp_reference VARCHAR(64),
  outcome VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_webhook_event_event_id UNIQUE (event_id)
);

CREATE INDEX idx_webhook_event_psp_reference ON webhook_event(psp_reference);
