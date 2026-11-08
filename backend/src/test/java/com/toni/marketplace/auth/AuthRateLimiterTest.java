package com.toni.marketplace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  @Test
  void snapshotReportsFullBucketBeforeAnyConsume() {
    AuthRateLimiter.RateLimitState state = limiter.snapshot("10.0.0.10");
    assertThat(state.limit()).isEqualTo(5);
    assertThat(state.remaining()).isEqualTo(5);
    assertThat(state.resetSeconds()).isEqualTo(0);
  }

  @Test
  void snapshotRemainingDecrementsAcrossSuccessiveConsumes() {
    // The 429 itself consumes nothing, so the decrement is pinned here at
    // the limiter level: 5 -> 4 -> 3 -> 2 -> 1 -> 0 across the burst.
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryConsume("10.0.0.11")).isTrue();
      assertThat(limiter.snapshot("10.0.0.11").remaining())
          .as("remaining after consume %d", i + 1)
          .isEqualTo(4 - i);
    }
    assertThat(limiter.snapshot("10.0.0.11").remaining()).isEqualTo(0);
  }

  @Test
  void snapshotOnExhaustedBucketReportsZeroRemainingAndFullReset() {
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume("10.0.0.12");
    }
    AuthRateLimiter.RateLimitState state = limiter.snapshot("10.0.0.12");
    assertThat(state.limit()).isEqualTo(5);
    assertThat(state.remaining()).isEqualTo(0);
    // 5 tokens at 5/min: a full refill takes exactly 60 s.
    assertThat(state.resetSeconds()).isEqualTo(60);
  }

  @Test
  void snapshotDoesNotConsumeAndResetCountsDownWithRefill() {
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume("10.0.0.13");
    }
    assertThat(limiter.snapshot("10.0.0.13").resetSeconds()).isEqualTo(60);
    assertThat(limiter.snapshot("10.0.0.13").resetSeconds())
        .as("snapshot itself consumes nothing")
        .isEqualTo(60);
    // Denied attempts consume nothing either.
    assertThat(limiter.tryConsume("10.0.0.13")).isFalse();
    assertThat(limiter.snapshot("10.0.0.13").remaining()).isEqualTo(0);
    assertThat(limiter.snapshot("10.0.0.13").resetSeconds()).isEqualTo(60);

    clock.advance(Duration.ofSeconds(6)); // half a token refilled
    assertThat(limiter.snapshot("10.0.0.13").remaining())
        .as("half a token is not a whole token")
        .isEqualTo(0);
    assertThat(limiter.snapshot("10.0.0.13").resetSeconds()).isEqualTo(54);
  }

  @Test
  void constructorRejectsNonPositiveAttemptsPerMinute() {
    AuthRateLimitProperties zero = new AuthRateLimitProperties();
    zero.setAttemptsPerMinute(0);
    assertThatThrownBy(() -> new AuthRateLimiter(clock, zero))
        .isInstanceOf(IllegalArgumentException.class);

    AuthRateLimitProperties negative = new AuthRateLimitProperties();
    negative.setAttemptsPerMinute(-3);
    assertThatThrownBy(() -> new AuthRateLimiter(clock, negative))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledLimiterAlwaysAllowsAndReportsFullState() {
    AuthRateLimitProperties props = new AuthRateLimitProperties();
    props.setAttemptsPerMinute(5);
    props.setEnabled(false);
    AuthRateLimiter disabled = new AuthRateLimiter(clock, props);

    assertThat(disabled.isEnabled()).isFalse();
    for (int i = 0; i < 10; i++) {
      assertThat(disabled.tryConsume("10.0.0.14")).as("disabled attempt %d", i + 1).isTrue();
    }
    AuthRateLimiter.RateLimitState state = disabled.snapshot("10.0.0.14");
    assertThat(state.limit()).isEqualTo(5);
    assertThat(state.remaining()).isEqualTo(5);
    assertThat(state.resetSeconds()).isEqualTo(0);
    assertThat(disabled.retryAfterSeconds("10.0.0.14")).isEqualTo(0);
  }

  @Test
  void nullAndBlankKeysShareTheUnknownBucket() {
    assertThat(limiter.isEnabled()).isTrue();
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryConsume(null)).as("null-key attempt %d", i + 1).isTrue();
    }
    // Null/blank keys never bypass the limit: they share one bucket, so
    // blank keys hit the same exhausted bucket the null key filled.
    assertThat(limiter.tryConsume(null)).isFalse();
    assertThat(limiter.tryConsume("")).isFalse();
    assertThat(limiter.tryConsume("   ")).isFalse();
    // A real key is untouched by the unknown bucket's exhaustion.
    assertThat(limiter.tryConsume("10.0.0.15")).isTrue();
  }

  @Test
  void windowedBucketRefillsAcrossTheWindowNotPerMinute() {
    // Backlog #354: the per-address mail bucket is 5 per HOUR, not per
    // minute — a per-minute bucket of the same capacity would still let
    // ~300 mails a day through to one victim.
    AuthRateLimiter hourly = new AuthRateLimiter(clock, 5, Duration.ofHours(1), true);
    for (int i = 0; i < 5; i++) {
      assertThat(hourly.tryConsume("victim@example.com")).as("attempt %d", i + 1).isTrue();
    }
    assertThat(hourly.tryConsume("victim@example.com")).as("6th attempt").isFalse();

    // One minute refills nothing at 5/hour (a token takes 12 minutes).
    clock.advance(Duration.ofMinutes(1));
    assertThat(hourly.tryConsume("victim@example.com"))
        .as("no token after one minute")
        .isFalse();

    // Twelve minutes refills exactly one token.
    clock.advance(Duration.ofMinutes(11));
    assertThat(hourly.tryConsume("victim@example.com"))
        .as("one token after twelve minutes")
        .isTrue();
    assertThat(hourly.tryConsume("victim@example.com")).isFalse();

    // A full hour from the last consume refills the bucket completely.
    clock.advance(Duration.ofMinutes(60));
    for (int i = 0; i < 5; i++) {
      assertThat(hourly.tryConsume("victim@example.com"))
          .as("refilled attempt %d", i + 1).isTrue();
    }
    assertThat(hourly.tryConsume("victim@example.com")).isFalse();
  }

  @Test
  void windowedRetryAfterAndResetSpanTheWindow() {
    AuthRateLimiter hourly = new AuthRateLimiter(clock, 5, Duration.ofHours(1), true);
    for (int i = 0; i < 5; i++) {
      hourly.tryConsume("victim@example.com");
    }
    // One token every 12 minutes: the next token is exactly 720 s away,
    // and a full bucket is an hour away.
    assertThat(hourly.retryAfterSeconds("victim@example.com")).isEqualTo(720);
    AuthRateLimiter.RateLimitState state = hourly.snapshot("victim@example.com");
    assertThat(state.limit()).isEqualTo(5);
    assertThat(state.remaining()).isEqualTo(0);
    assertThat(state.resetSeconds()).isEqualTo(3600);
  }

  @Test
  void windowedConstructorRejectsNonPositiveAttemptsOrWindow() {
    assertThatThrownBy(() -> new AuthRateLimiter(clock, 0, Duration.ofHours(1), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthRateLimiter(clock, 5, Duration.ZERO, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthRateLimiter(clock, 5, Duration.ofMinutes(-1), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuthRateLimiter(clock, 5, null, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
