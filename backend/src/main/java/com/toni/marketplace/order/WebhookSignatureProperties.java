package com.toni.marketplace.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.webhooks.*}: mock-PSP webhook signature policy (backlog #149).
 *
 * <ul>
 *   <li>{@code signature-tolerance}: the freshness window for the
 *   Stripe-style signature header ({@code t=<unix seconds>,v1=<hmac>},
 *   HMAC-SHA256 over {@code "<t>.<raw body>"}). A delivery whose timestamp
 *   differs from the server clock (injected {@code Clock}) by more than
 *   this — stale OR far-future — is rejected with the same 401 envelope as
 *   a bad signature. Default 5 minutes. This binds the life of a captured
 *   delivery: before #149 the HMAC covered the body only, so a captured
 *   delivery never expired and replay defence rested entirely on the
 *   {@code webhook_event} event-id ledger (#140), which dedupes a re-sent
 *   event but cannot reject a stale FIRST delivery of an old capture.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.webhooks")
public class WebhookSignatureProperties {

  /** Freshness window for webhook signature timestamps. */
  private Duration signatureTolerance = Duration.ofMinutes(5);

  public Duration getSignatureTolerance() {
    return signatureTolerance;
  }

  public void setSignatureTolerance(Duration signatureTolerance) {
    this.signatureTolerance = signatureTolerance;
  }
}
