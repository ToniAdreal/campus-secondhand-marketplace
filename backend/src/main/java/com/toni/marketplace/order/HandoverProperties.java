package com.toni.marketplace.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Handover-code brute-force policy for
 * {@code POST /api/orders/{id}/complete} (backlog #179).
 *
 * <p>The code is 6 digits (10^6 possibilities) presented by the seller at
 * handover, so the complete exchange is an online-guessing surface.
 * Consecutive failed attempts are tracked per order on the order row
 * (Flyway V43): after {@code maxAttempts} consecutive failures the code
 * locks for {@code lockDuration} and even the correct code is refused
 * with the identical 400 — no oracle distinguishes "wrong", "missing"
 * and "locked". A successful completion (or a buyer regeneration) resets
 * the counter; an expired lock resets it on the next attempt.
 *
 * <p>Deliberate separation from the account lockouts (#60/#101): the
 * counter and lock live on the ORDER, not on any account, and they gate
 * only the complete exchange. A locked code never locks the buyer out of
 * the dispute flow (#169) — the dispute is precisely the recourse a
 * buyer needs when a handover goes wrong.
 *
 * <p>Defaults mirror the login/TOTP lockouts: 5 failures, 15-minute
 * lock. Override with e.g. {@code app.orders.handover.max-attempts=3}.
 *
 * <p>Backlog #234 adds a code lifetime: a minted code expires
 * {@code ttl} after minting (default 7 days — long enough for a buyer
 * and seller to meet, short enough that a leaked code dies on its
 * own). An expired code is refused at complete with 422 pointing at
 * the regenerate endpoint, and presenting it never feeds the failure
 * counter — expiry is a state, not a guess. Codes minted before #234
 * carry a null expiry and are grandfathered: they never expire by
 * time (they still die on use, regeneration, or a terminal order
 * transition), so this change cannot strand an in-flight handover
 * that was valid when it was issued.
 */
@ConfigurationProperties(prefix = "app.orders.handover")
public class HandoverProperties {

  /** Consecutive failed complete attempts after which the code locks. Must be &ge; 1. */
  private int maxAttempts = 5;

  /** How long the handover code stays locked. Must be positive. */
  private Duration lockDuration = Duration.ofMinutes(15);

  /** How long a minted handover code stays valid. Must be positive. */
  private Duration ttl = Duration.ofDays(7);

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getLockDuration() {
    return lockDuration;
  }

  public void setLockDuration(Duration lockDuration) {
    this.lockDuration = lockDuration;
  }

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }
}
