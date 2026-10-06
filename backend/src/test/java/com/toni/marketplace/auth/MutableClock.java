package com.toni.marketplace.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test clock whose instant starts at {@link Instant#now()} and can be
 * advanced deterministically. Registering it as the {@code Clock} bean (via
 * {@code @Primary}) drives every time-dependent component — the
 * {@link AuthRateLimiter} and the JWT service — under test control.
 */
public class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  public MutableClock() {
    this(Instant.now());
  }

  public MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  /** Moves the clock forward; never backwards. */
  public void advance(Duration duration) {
    if (duration.isNegative()) {
      throw new IllegalArgumentException("clock only moves forward in tests");
    }
    now.updateAndGet(current -> current.plus(duration));
  }

  @Override
  public ZoneId getZone() {
    return ZoneId.of("UTC");
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("test clock is fixed to UTC");
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
