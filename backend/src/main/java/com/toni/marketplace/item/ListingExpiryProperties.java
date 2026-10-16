package com.toni.marketplace.item;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.listings.*}: the listing lifetime policy (backlog #177).
 *
 * <ul>
 *   <li>{@code ttl}: how long a listing stays live after creation (or
 *   after a renew) before it expires. Default 30 days. Expiry is
 *   lazy-on-read — the public browse/detail reads treat a past-deadline
 *   AVAILABLE listing as gone the moment the deadline passes — and the
 *   nightly {@link ListingExpiryService} flips the stored status to
 *   EXPIRED for reporting, so the two paths agree by construction
 *   (both use {@link Item#isExpiredAt}).</li>
 *   <li>{@code renew-window}: how close to the deadline a still-live
 *   AVAILABLE listing must be before renew is accepted. Renewing a
 *   listing whose deadline is further away than this window is 422 —
 *   renew is for expired or expiring listings, not a free lifetime
 *   reset on day one. Default 7 days.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.listings")
public class ListingExpiryProperties {

  /** How long a listing stays live after creation / renew. */
  private Duration ttl = Duration.ofDays(30);

  /** A live listing may be renewed once its deadline is within this window. */
  private Duration renewWindow = Duration.ofDays(7);

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }

  public Duration getRenewWindow() {
    return renewWindow;
  }

  public void setRenewWindow(Duration renewWindow) {
    this.renewWindow = renewWindow;
  }
}
