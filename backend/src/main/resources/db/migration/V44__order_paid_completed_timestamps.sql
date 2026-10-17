-- V44: real paid_at / completed_at timestamps on the order row (backlog #180).
--
-- Until now orders carried only created_at / updated_at, so a receipt
-- could not honestly say WHEN an order was paid or completed (the dispute
-- window in #169 had to measure from updated_at and document the
-- substitution). OrderService.pay now stamps paid_at and
-- OrderService.complete stamps completed_at from the injected Clock, in
-- the same transaction as the status transition.
--
-- No backfill: rows paid/completed before this migration keep NULL --
-- their transition instants were never recorded, and deriving them from
-- updated_at would fabricate a timestamp the receipt would then present
-- as fact. Receipts for such legacy rows show null paid/completed
-- timestamps (the capture/refund references on the row still prove the
-- transition happened).
--
-- Portability: plain H2 rejects multi-ADD-COLUMN in one ALTER, so two
-- statements -- the same reason V11, V13, V14, V25 and V43 split theirs.
ALTER TABLE orders ADD COLUMN paid_at TIMESTAMP NULL;
ALTER TABLE orders ADD COLUMN completed_at TIMESTAMP NULL;
