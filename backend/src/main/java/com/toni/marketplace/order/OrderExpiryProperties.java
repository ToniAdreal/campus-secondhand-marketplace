package com.toni.marketplace.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.orders.*}: tuning knobs for the order domain's background
 * behavior.
 *
 * <ul>
 *   <li>{@code pending-ttl}: how long a PENDING order may live before
 *   {@link PendingOrderExpiryService} cancels it and releases the listing
 *   back to AVAILABLE. Default 30 minutes. Expiry granularity is the job's
 *   5-minute cadence, so a stale order holds its listing at most TTL + one
 *   cadence past creation.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.orders")
public class OrderExpiryProperties {

  /** How long a PENDING order may live before the expiry job cancels it. */
  private Duration pendingTtl = Duration.ofMinutes(30);

  public Duration getPendingTtl() {
    return pendingTtl;
  }

  public void setPendingTtl(Duration pendingTtl) {
    this.pendingTtl = pendingTtl;
  }
}
