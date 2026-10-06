-- V5: idempotency_key table — backs idempotent POST /api/orders.
-- Runs on both MySQL 8 (prod/CI) and H2 in MySQL-compatibility mode (local dev).
--
-- One row per (user, Idempotency-Key): UNIQUE(user_id, idempotency_key) is the
-- atomic arbiter when two requests race with the same key — exactly one of
-- them wins the insert, the loser re-reads the winner's committed record.
-- Keys are scoped per user: two different buyers may coincidentally send the
-- same key without colliding.
--
-- status lifecycle: IN_PROGRESS (request accepted, order not yet created) ->
-- COMPLETED (order_id set) or FAILED (creation threw; the client must retry
-- with a NEW key — reusing a failed key can never resurrect the attempt).
-- item_id is stored so a key reused with a *different* payload is rejected
-- 422 instead of silently returning the wrong order.
-- A stale IN_PROGRESS row (owner crashed between reserve and complete) may be
-- reclaimed by the application after 10 minutes; see IdempotencyKeyService.

CREATE TABLE idempotency_key (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  item_id BIGINT NOT NULL,
  order_id BIGINT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_idem_user FOREIGN KEY (user_id) REFERENCES app_user(id),
  CONSTRAINT fk_idem_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT uq_idem_user_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_idem_order ON idempotency_key(order_id);
