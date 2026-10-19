-- V51: counter-offer price on price offers (backlog #193). The seller's
-- single counter to a PENDING offer stores its price on the offer row
-- itself (counter_price_cents, NULL until the seller counters); the
-- buyer's original price_cents is never rewritten, so the row keeps the
-- full negotiation: offered price, counter price, and — on acceptance —
-- the order created at the counter price.
--
-- The column add is portable (one statement per ALTER, mirroring V34's
-- notification.offer_id add). Extending the live_offer_key guard to
-- COUNTERED rows is vendor-specific (dropping the unique guard differs
-- per database) and lives in db/vendor/{vendor}/V52.

ALTER TABLE price_offer ADD COLUMN counter_price_cents BIGINT;
