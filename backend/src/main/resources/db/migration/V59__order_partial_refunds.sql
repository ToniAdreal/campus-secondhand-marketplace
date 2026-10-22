-- V59: partial refunds (backlog #210).
--
-- Before #210, POST /api/orders/{id}/refund was all-or-nothing: the
-- order's full amountCents or nothing, and the order row carried no
-- refunded-amount field, so a seller who owed a buyer part of the
-- price (damaged corner, missing accessory) had to refund everything
-- or nothing.
--
-- refunded_amount_cents is the cumulative total refunded so far. The
-- invariant every refund path maintains: 0 <= refunded_amount_cents
-- <= amount_cents, and status = REFUNDED exactly when the cumulative
-- total reaches amount_cents. The backfill below restores that
-- invariant for orders refunded before this migration (they were
-- full refunds by construction).
ALTER TABLE orders ADD COLUMN refunded_amount_cents BIGINT NOT NULL DEFAULT 0;
UPDATE orders SET refunded_amount_cents = amount_cents WHERE status = 'REFUNDED';

-- refund_count is the number of refund calls that reached the PSP
-- seam for this order. It is the sequence the per-refund idempotency
-- key is derived from (rfd_<orderId>_<seq>): because the count only
-- moves at the commit that records the refund, a retry after a crash
-- between the PSP call and the commit re-derives the SAME key and the
-- mock replays instead of refunding twice, while the next distinct
-- refund derives a fresh key that is never reused. Pre-#210 full
-- refunds used the single stored refund_idempotency_key; their count
-- backfills to 1 so a later key derivation can never collide with a
-- sequence those refunds conceptually occupied.
ALTER TABLE orders ADD COLUMN refund_count INT NOT NULL DEFAULT 0;
UPDATE orders SET refund_count = 1 WHERE status = 'REFUNDED';
