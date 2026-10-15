package com.toni.marketplace.offer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.offers.*}: tuning knobs for the price-offer domain
 * (backlog #168).
 *
 * <ul>
 *   <li>{@code pending-ttl}: how long a PENDING offer may wait for the
 *   seller's answer before {@link OfferExpiryService} flips it to
 *   EXPIRED. Default 48 hours — a seller needs a realistic window to
 *   respond (unlike a PENDING order's 30 minutes, no listing is held
 *   hostage meanwhile: an unanswered offer reserves nothing). Expiry
 *   granularity is the job's 5-minute cadence, so a stale offer lives
 *   at most TTL + one cadence past its deadline.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.offers")
public class OfferProperties {

  /** How long a PENDING offer may live before the expiry job expires it. */
  private Duration pendingTtl = Duration.ofHours(48);

  public Duration getPendingTtl() {
    return pendingTtl;
  }

  public void setPendingTtl(Duration pendingTtl) {
    this.pendingTtl = pendingTtl;
  }
}
