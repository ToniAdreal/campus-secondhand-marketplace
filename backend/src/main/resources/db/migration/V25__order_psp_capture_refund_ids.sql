-- V25: persist the mock PSP capture/refund ids on the order row
-- (backlog #114).
--
-- PaymentService mints cap_mock_*/rfd_mock_* ids (#65) but until now
-- OrderService.pay/refund discarded the returned CaptureResult /
-- RefundResult, so no screen, log query or reconciliation could ever
-- name the PSP reference for a paid or refunded order -- the exact
-- reference a real PSP integration would be reconciled by. The ids are
-- now stored on the row in the same transaction as the state
-- transition. Nullable: NULL until the transition has happened, and a
-- declined capture (#92) stores nothing.
--
-- Portability: plain H2 (the @DataJpaTest slice tests) rejects
-- multi-ADD-COLUMN in one ALTER, so two statements -- the same reason
-- V11, V13 and V14 split their column adds (see their headers).
ALTER TABLE orders ADD COLUMN capture_id VARCHAR(64);
ALTER TABLE orders ADD COLUMN refund_id VARCHAR(64);
