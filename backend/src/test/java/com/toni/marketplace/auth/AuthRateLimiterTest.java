package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the {@link AuthRateLimiter} token-bucket math, with a
 * {@link MutableClock} standing in for wall time. No Spring context.
 */
class AuthRateLimiterTest {

  private MutableClock clock;
  private AuthRateLimiter limiter;

  @BeforeEach
  void setUp() {
    // Fixed epoch start keeps the arithmetic legible; production starts at
    // wall-clock time, which the math treats identically.
    clock = new MutableClock(Instant.EPOCH);
    AuthRateLimitProperties props = new AuthRateLimitProperties();
    props.setAttemptsPerMinute(5);
    limiter = new AuthRateLimiter(clock, props);
  }

  @Test
  void burstOfFivePassesThenBucketIsEmpty() {
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryConsume("10.0.0.1")).as("attempt %d", i + 1).isTrue();
    }
    assertThat(limiter.tryConsume("10.0.0.1")).as("6th attempt").isFalse();
    assertThat(limiter.tryConsume("10.0.0.1")).as("7th attempt").isFalse();
  }

  @Test
  void retryAfterIsTwelveSecondsOnFreshExhaustion() {
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume("10.0.0.2");
    }
    // One token every 12 s: the next token is exactly 12 s away.
    assertThat(limiter.retryAfterSeconds("10.0.0.2")).isEqualTo(12);
  }

  @Test
  void retryAfterCountsDownAsTimePasses() {
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume("10.0.0.3");
    }
    clock.advance(Duration.ofSeconds(6));
    assertThat(limiter.tryConsume("10.0.0.3")).as("only half a token refilled").isFalse();
    assertThat(limiter.retryAfterSeconds("10.0.0.3")).isEqualTo(6);

    clock.advance(Duration.ofSeconds(6));
    assertThat(limiter.tryConsume("10.0.0.3")).as("one full token refilled").isTrue();
    assertThat(limiter.retryAfterSeconds("10.0.0.3")).isEqualTo(12);
  }

  @Test
  void bucketRefillsFullyAfterOneMinute() {
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume("10.0.0.4");
    }
    clock.advance(Duration.ofMinutes(1));
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryConsume("10.0.0.4")).as("refilled attempt %d", i + 1).isTrue();
    }
    assertThat(limiter.tryConsume("10.0.0.4")).isFalse();
  }

  @Test
  void refillNeverExceedsCapacity() {
    limiter.tryConsume("10.0.0.5"); // 4 left
    clock.advance(Duration.ofHours(1)); // far more than a full refill
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryConsume("10.0.0.5")).as("attempt %d", i + 1).isTrue();
    }
    assertThat(limiter.tryConsume("10.0.0.5")).as("capped at capacity").isFalse();
  }

  @Test
  void resetRestoresFullBucket() {
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume("10.0.0.6");
    }
    assertThat(limiter.tryConsume("10.0.0.6")).isFalse();
    limiter.reset("10.0.0.6");
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryConsume("10.0.0.6")).as("post-reset attempt %d", i + 1).isTrue();
    }
    assertThat(limiter.tryConsume("10.0.0.6")).isFalse();
  }

  @Test
  void bucketsAreIsolatedPerKey() {
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume("10.0.0.7");
    }
    assertThat(limiter.tryConsume("10.0.0.7")).isFalse();
    assertThat(limiter.tryConsume("10.0.0.8")).as("other key unaffected").isTrue();
  }

  @Test
  void resetOnUnknownKeyIsHarmless() {
    limiter.reset("never-seen");
    assertThat(limiter.tryConsume("never-seen")).isTrue();
  }

  @Test
  void retryAfterIsZeroWhenTokenAvailable() {
    assertThat(limiter.retryAfterSeconds("10.0.0.9")).isEqualTo(0);
  }
}
