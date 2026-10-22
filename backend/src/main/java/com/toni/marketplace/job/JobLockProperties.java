package com.toni.marketplace.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single-instance scheduler guard settings (backlog #215), bound in
 * {@link CleanupConfig} next to the retention policy.
 *
 * <p>The lease must comfortably outlive the longest single job run (the
 * nightly sweeps are seconds-to-minutes), while staying short enough
 * that a crashed holder delays its job by at most a run or two: with
 * the 10-minute default, a 5-minute-cadence job whose holder dies
 * mid-run skips at most one further firing before the lease is
 * stealable, and the nightly jobs are unaffected in practice. Release
 * on completion is the normal path — the lease only ever decides who
 * recovers from a crash. Override with e.g.
 * {@code app.jobs.lock-lease=2m} (tests do exactly this via short
 * leases and a controllable clock instead).
 */
@ConfigurationProperties(prefix = "app.jobs")
public class JobLockProperties {

  /** How long one acquisition holds the lease without release. */
  private Duration lockLease = Duration.ofMinutes(10);

  public Duration getLockLease() {
    return lockLease;
  }

  public void setLockLease(Duration lockLease) {
    this.lockLease = lockLease;
  }
}
