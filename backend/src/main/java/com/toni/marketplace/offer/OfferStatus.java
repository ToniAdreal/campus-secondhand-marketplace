package com.toni.marketplace.offer;

/**
 * Lifecycle of a price offer (backlog #168). PENDING is the only live
 * state — it is the value the Flyway V34 {@code live_offer_key} guard
 * column keys on, so a buyer holds at most one PENDING offer per
 * listing. ACCEPTED / DECLINED / WITHDRAWN are the parties' decisions;
 * EXPIRED is the expiry job's terminal state for offers nobody answered
 * inside {@code app.offers.pending-ttl}. Every non-PENDING state is
 * terminal: there is no counter-offer chain in v1 — a declined or
 * withdrawn offer is final, and the buyer may submit a fresh offer.
 */
public enum OfferStatus {
  PENDING,
  ACCEPTED,
  DECLINED,
  EXPIRED,
  WITHDRAWN
}
