-- V60: durable payment-attempt ledger (backlog #211).
--
-- Before #211 a declined capture existed only as a Micrometer counter
-- and a log line: PaymentService throws PaymentDeclinedException before
-- anything is persisted, so neither the buyer ("why did my payment
-- fail?"), the seller, nor the ADMIN payments view could see that an
-- attempt had happened at all, and retry-after-decline behaviour was
-- unprovable from data.
--
-- One row per capture attempt against an order: the order id (a plain
-- column, deliberately no FK -- like webhook_event this is an audit
-- ledger, not a second source of truth for order state), the outcome
-- (SUCCEEDED / DECLINED), the order-scoped PSP idempotency key the
-- attempt ran under (backlog #65 -- the key is an internal correlation
-- id, safe to show an operator), and the attempt instant.
--
-- The buyer's payment token is NEVER stored, in any column, in any
-- form: the decline seam's tok_decline marker (backlog #92) is a
-- credential-shaped value and the ledger has no column that could
-- hold it.
CREATE TABLE payment_attempt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  idempotency_key VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_attempt_order_created ON payment_attempt(order_id, created_at);
CREATE INDEX idx_payment_attempt_outcome_created ON payment_attempt(outcome, created_at);
