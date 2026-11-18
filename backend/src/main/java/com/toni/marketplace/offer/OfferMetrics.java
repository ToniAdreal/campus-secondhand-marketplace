package com.toni.marketplace.offer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Offer-domain Micrometer counters (backlog #393), mirroring
 * {@code OrderMetrics} (#93 / #348) and exported on
 * {@code /actuator/prometheus} (#57) alongside the JVM, auth and order
 * metrics.
 *
 * <ul>
 *   <li>{@code offers.created} — a fresh offer was created. A second
 *       POST while a live offer exists is a 409 and does not count;
 *       neither does a racing create that loses the
 *       {@code uq_offer_live_pair} arbiter to the same 409.</li>
 *   <li>{@code offers.accepted} — the seller accepted a PENDING offer
 *       and the order was created at the agreed price. A sequential
 *       re-accept is a 422 and a concurrent one loses the
 *       {@code @Version} / active-order race to 409 — neither counts.</li>
 *   <li>{@code offers.declined} — an offer reached terminal DECLINED,
 *       whether the seller declined a PENDING offer or the buyer
 *       declined the seller's counter (the audit trail records both
 *       as {@code OFFER_DECLINED} too, told apart by the actor). A
 *       second decline is a 422 and does not count.</li>
 *   <li>{@code offers.countered} — the seller answered a PENDING
 *       offer with a counter price (backlog #193). A second counter
 *       is a 422 and does not count.</li>
 *   <li>{@code offers.counter_accepted} — the buyer accepted the
 *       seller's counter and the order was created at the COUNTER
 *       price. A sequential re-accept is a 422 and a concurrent one
 *       loses the race to 409 — neither counts.</li>
 *   <li>{@code offers.expired} — a live offer lapsed past its
 *       deadline and the expiry job flipped it to EXPIRED. Counted
 *       on the job's flip only: the lazy deadline flip inside the
 *       decision endpoints rolls back with the rejected decision and
 *       writes no row (backlog #389), so it counts nothing here
 *       either — a sweep re-run finds the row EXPIRED and counts
 *       nothing twice.</li>
 * </ul>
 *
 * <p>Deliberately NOT counted: withdraw (the buyer retracting their
 * own offer — visible in the acting party's own offer list, and the
 * audit trail writes no row for it either) and the lazy deadline flip
 * (see above). Counters increment at exactly the transition points in
 * {@link OfferService}, after the deliberate in-transaction flush
 * that surfaces a lost {@code @Version} race as 409 — so a transition
 * that loses a race (or rolls back) is never counted. The event is
 * encoded in the counter name itself — deliberately no tags carrying
 * usernames, offer ids, or IPs: tag values would be both a
 * cardinality hazard and a PII leak into the metrics store. Honest
 * scope: these meters exist and are scrapeable; alert rules over them
 * are a separate follow-up (backlog #400, the #350/#353 pattern) —
 * this item adds counters only, no rule.
 */
@Component
public class OfferMetrics {

  private final Counter created;
  private final Counter accepted;
  private final Counter declined;
  private final Counter countered;
  private final Counter counterAccepted;
  private final Counter expired;

  public OfferMetrics(MeterRegistry registry) {
    MeterRegistry reg = registry != null ? registry : new SimpleMeterRegistry();
    this.created = Counter.builder("offers.created")
        .description("Fresh price offers created (live-pair 409s excluded)")
        .register(reg);
    this.accepted = Counter.builder("offers.accepted")
        .description("PENDING offers accepted by the seller, order created at the agreed price")
        .register(reg);
    this.declined = Counter.builder("offers.declined")
        .description("Offers declined by the seller or counter-declined by the buyer")
        .register(reg);
    this.countered = Counter.builder("offers.countered")
        .description("PENDING offers answered with a seller counter price")
        .register(reg);
    this.counterAccepted = Counter.builder("offers.counter_accepted")
        .description("Countered offers accepted by the buyer, order created at the counter price")
        .register(reg);
    this.expired = Counter.builder("offers.expired")
        .description("Live offers flipped to EXPIRED by the expiry job")
        .register(reg);
  }

  /**
   * Counters on a throwaway registry — for code constructed outside
   * Spring (direct unit tests) that does not assert on metrics. The
   * increments still happen; they are simply never scraped.
   */
  public static OfferMetrics noop() {
    return new OfferMetrics(new SimpleMeterRegistry());
  }

  public void created() {
    created.increment();
  }

  public void accepted() {
    accepted.increment();
  }

  public void declined() {
    declined.increment();
  }

  public void countered() {
    countered.increment();
  }

  public void counterAccepted() {
    counterAccepted.increment();
  }

  public void expired() {
    expired.increment();
  }
}
