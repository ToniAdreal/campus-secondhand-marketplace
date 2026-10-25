package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Frontend error telemetry intake policy (backlog #223).
 *
 * <p>The body cap for {@code POST /api/telemetry/errors} (see
 * {@link TelemetryErrorController}). A genuine beacon carries three short
 * fields — the error name, the route template and the app version — so
 * 4 KB is generous headroom while keeping the public, unauthenticated
 * endpoint from accepting arbitrarily large payloads. The per-IP rate
 * limit for the endpoint lives with the other buckets in
 * {@link AuthRateLimitProperties#getTelemetryErrorAttemptsPerMinute()}.
 */
@ConfigurationProperties(prefix = "app.telemetry.errors")
public class TelemetryErrorProperties {

  /**
   * Hard cap on the raw beacon body, in bytes. Bodies over the cap are
   * answered {@code 413} in the {@code {code,message,data}} envelope
   * before any parsing happens. Must be &ge; 1.
   */
  private int maxBodyBytes = 4096;

  public int getMaxBodyBytes() {
    return maxBodyBytes;
  }

  public void setMaxBodyBytes(int maxBodyBytes) {
    this.maxBodyBytes = maxBodyBytes;
  }
}
