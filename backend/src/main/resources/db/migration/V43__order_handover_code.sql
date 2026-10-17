-- V43: buyer handover confirmation code on the order row (backlog #179).
--
-- When an order becomes PAID, OrderService.pay mints a 6-digit handover
-- code for the BUYER. Only the code's SHA-256 hash is stored here -- the
-- plaintext is returned exactly once, in the pay response (and once more
-- if the buyer regenerates, which replaces the hash and invalidates the
-- old code). POST /api/orders/{id}/complete requires a seller caller to
-- present the code; an ADMIN completion is an explicit override audited
-- under its own action (ORDER_COMPLETED_ADMIN_OVERRIDE).
--
-- The two extra columns mirror the #60/#101 lockout shape: consecutive
-- failed complete attempts are counted per order, and after
-- app.orders.handover.max-attempts failures the code locks until
-- handover_locked_until (attempts are recorded in a REQUIRES_NEW
-- transaction so the 400 rollback cannot erase them). The lock gates
-- ONLY the complete exchange -- it never touches the buyer's dispute
-- path (#169).
--
-- Portability: plain H2 (the @DataJpaTest slice tests) rejects
-- multi-ADD-COLUMN in one ALTER, so three statements -- the same reason
-- V11, V13, V14 and V25 split their column adds (see their headers).
ALTER TABLE orders ADD COLUMN handover_code_hash VARCHAR(64);
ALTER TABLE orders ADD COLUMN handover_failed_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN handover_locked_until TIMESTAMP NULL;
