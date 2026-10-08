-- V14: order-scoped PSP idempotency keys (backlog #65 — implements #45's
-- declared follow-up "a real PSP capture must carry an order-scoped
-- idempotency key").
--
-- PaymentService.capture/refund now take an idempotency key; the key is
-- generated once per order transition by OrderService and stored on the
-- order row, so a retry after a crash between the PSP capture/refund and
-- the commit reuses the stored key instead of issuing a second charge or
-- refund. Capture and refund get distinct keys (and distinct key
-- namespaces in the mock), one column each. Nullable: orders that have
-- never been paid/refunded simply carry NULL.
--
-- Portability: plain H2 (the @DataJpaTest slice tests) rejects
-- multi-ADD-COLUMN in one ALTER, so two statements — the same reason V11
-- and V13 split their column adds (see their headers).
ALTER TABLE orders ADD COLUMN capture_idempotency_key VARCHAR(64);
ALTER TABLE orders ADD COLUMN refund_idempotency_key VARCHAR(64);
