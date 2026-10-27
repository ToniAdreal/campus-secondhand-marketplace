-- V69: direct-refund reason on the payment-attempt ledger (backlog #239).
--
-- Before #239 a direct refund moved money with no recorded why: the
-- refund endpoint carried only amountCents, and the payment-attempt
-- ledger (V60) recorded capture attempts only. The new REFUNDED row
-- (written by OrderService.refund in the refund transaction) carries
-- the reason here; capture rows keep NULL, and dispute-resolved
-- refunds are out of scope — their note is backlog #240's field.
ALTER TABLE payment_attempt ADD COLUMN refund_reason VARCHAR(500);
