package com.toni.marketplace.offer;

/**
 * Lifecycle of a price offer (backlog #168). PENDING and COUNTERED are
 * the live states — they are the values the Flyway V34/V52 {@code
 * live_offer_key} guard column keys on, so a buyer holds at most one
 * live offer per listing. COUNTERED (backlog #193) is the seller's one
 * counter to a PENDING offer: it carries the counter price and waits on
 * the buyer, who either accepts it (the offer flips to ACCEPTED and an
 * order is created at the counter price) or declines it (the offer
 * flips to DECLINED, terminal). ACCEPTED / DECLINED / WITHDRAWN are the
 * parties' decisions; EXPIRED is the expiry job's terminal state for
 * live offers nobody answered inside {@code app.offers.pending-ttl}.
 * There is at most one counter per offer — no back-and-forth chain.
 */
public enum OfferStatus {
  PENDING,
  COUNTERED,
  ACCEPTED,
  DECLINED,
  EXPIRED,
  WITHDRAWN
}
