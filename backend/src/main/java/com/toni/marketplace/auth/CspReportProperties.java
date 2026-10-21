package com.toni.marketplace.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CSP violation report intake policy (backlog #204).
 *
 * <p>The body cap for {@code POST /api/security/csp-report} (see
 * {@link CspReportController}). A genuine browser report is a few
 * hundred bytes (legacy {@code report-uri} JSON) or a small array of
 * them (Reporting API); 16 KB leaves generous headroom for either
 * shape while keeping the public, unauthenticated endpoint from
 * accepting arbitrarily large payloads. The per-IP rate limit for the
 * endpoint lives with the other buckets in
 * {@link AuthRateLimitProperties#getCspReportAttemptsPerMinute()}.
 */
@ConfigurationProperties(prefix = "app.security.csp-report")
public class CspReportProperties {

  /**
   * Hard cap on the raw report body, in bytes. Bodies over the cap are
   * answered {@code 413} in the {@code {code,message,data}} envelope
   * before any parsing happens. Must be &ge; 1.
   */
  private int maxBodyBytes = 16_384;

  public int getMaxBodyBytes() {
    return maxBodyBytes;
  }

  public void setMaxBodyBytes(int maxBodyBytes) {
    this.maxBodyBytes = maxBodyBytes;
  }
}
