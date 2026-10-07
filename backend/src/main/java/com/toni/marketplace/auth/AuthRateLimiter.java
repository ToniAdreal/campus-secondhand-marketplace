package com.toni.marketplace.auth;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-bucket rate limiter, one bucket per key (used with the
 * client IP). No new dependency: plain {@link ConcurrentHashMap} + an
 * injected {@link Clock} so tests can drive time deterministically.
 *
 * <p>Policy: token bucket sized and refilled at
 * {@code attemptsPerMinute} per client IP (see
 * {@link AuthRateLimitProperties}). A bucket holds at most that many tokens,
 * so a short burst passes but sustained hammering is throttled. Buckets are
 * created lazily and idle entries are evicted opportunistically to bound
 * memory (an attacker cycling IPs must not grow the map forever).
 *
 * <p>Instances are plain objects, not components: {@code AuthConfig} builds
 * one per throttled surface (credential endpoints vs. the refresh endpoint),
 * each with its own policy from {@link AuthRateLimitProperties}.
 *
 * <p>This is brute-force throttling, not a distributed rate limiter: with
 * more than one app instance each keeps its own buckets. Noted here so a
 * future horizontal scale-out knows to move this to Redis.
 */
public class AuthRateLimiter {

  private final int capacity;
  private final double refillTokensPerMillis;
  private final boolean enabled;

  private static final long MAX_TRACKED_KEYS = 10_000;
  private static final long EVICT_IDLE_MILLIS = 10 * 60_000L;

  private final Clock clock;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  public AuthRateLimiter(Clock clock, AuthRateLimitProperties props) {
    if (props.getAttemptsPerMinute() < 1) {
      throw new IllegalArgumentException("attemptsPerMinute must be >= 1");
    }
    this.clock = clock;
    this.enabled = props.isEnabled();
    this.capacity = props.getAttemptsPerMinute();
    this.refillTokensPerMillis = props.getAttemptsPerMinute() / 60_000.0;
  }

  /** Master switch from {@link AuthRateLimitProperties}. */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Tries to consume one token for {@code key}. Returns {@code true} when the
   * request may proceed, {@code false} when the bucket is empty (caller
   * should answer 429). Always allows when the limiter is disabled. Never
   * throws for a null/blank key — it is treated as a single shared "unknown"
   * bucket rather than bypassing the limit.
   */
  public boolean tryConsume(String key) {
    if (!enabled) {
      return true;
    }
    long now = clock.millis();
    evictIdleIfNeeded(now);
    Bucket bucket = buckets.computeIfAbsent(normalize(key), k -> new Bucket(now));
    synchronized (bucket) {
      bucket.refill(now);
      bucket.lastSeen = now;
      if (bucket.tokens >= 1.0) {
        bucket.tokens -= 1.0;
        return true;
      }
      // Float dust keeps the check fail-closed: a hair under one full token
      // still denies. For a security throttle, denying slightly early is the
      // safe direction.
      return false;
    }
  }

  /**
   * Whole seconds until the next token becomes available for {@code key},
   * for the {@code Retry-After} header. Returns 0 when a token is available
   * right now (or the limiter is disabled).
   */
  public long retryAfterSeconds(String key) {
    if (!enabled) {
      return 0;
    }
    long now = clock.millis();
    Bucket bucket = buckets.computeIfAbsent(normalize(key), k -> new Bucket(now));
    synchronized (bucket) {
      bucket.refill(now);
      if (bucket.tokens >= 1.0) {
        return 0;
      }
      double deficitMillis = (1.0 - bucket.tokens) / refillTokensPerMillis;
      // Epsilon guards the float overshoot on exact multiples: a deficit of
      // exactly 12_000 ms must yield 12 s, not 13.
      long millis = (long) Math.ceil(deficitMillis - 1e-9);
      return (millis + 999) / 1000;
    }
  }

  /**
   * Restores the full bucket for {@code key}. Used after a <em>successful</em>
   * login so a user who fat-fingered a few passwords and then got it right
   * is not locked out by their own earlier failures.
   */
  public void reset(String key) {
    Bucket bucket = buckets.get(normalize(key));
    if (bucket == null) {
      return;
    }
    synchronized (bucket) {
      bucket.tokens = capacity;
      long now = clock.millis();
      bucket.lastRefill = now;
      bucket.lastSeen = now;
    }
  }

  private static String normalize(String key) {
    return (key == null || key.isBlank()) ? "unknown" : key;
  }

  private void evictIdleIfNeeded(long now) {
    if (buckets.size() > MAX_TRACKED_KEYS) {
      buckets.entrySet().removeIf(e -> now - e.getValue().lastSeen > EVICT_IDLE_MILLIS);
    }
  }

  /** Mutable state is always accessed under the bucket's own monitor. */
  private final class Bucket {
    double tokens;
    long lastRefill;
    long lastSeen;

    Bucket(long now) {
      this.tokens = capacity;
      this.lastRefill = now;
      this.lastSeen = now;
    }

    void refill(long now) {
      long elapsed = now - lastRefill;
      if (elapsed > 0) {
        // A backwards clock jump is ignored rather than draining the bucket.
        tokens = Math.min(capacity, tokens + elapsed * refillTokensPerMillis);
        lastRefill = now;
      }
    }
  }
}
