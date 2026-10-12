package com.toni.marketplace.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mock-PSP webhook policy (backlog #140). Binds
 * {@code app.payments.webhook-secret}: the shared HMAC-SHA256 secret the
 * mock PSP signs {@code POST /api/payments/webhooks/mock-psp} deliveries
 * with (header {@code X-Mock-Psp-Signature}, Stripe-style
 * {@code t=<unix seconds>,v1=<hmac over "<t>.<raw body>">} since backlog
 * #149; the freshness window itself is
 * {@code app.webhooks.signature-tolerance} — see
 * {@link WebhookSignatureProperties}).
 *
 * <p>The committed default is a dev-only placeholder for local H2 runs.
 * On the {@code mysql} profile {@link WebhookSecretStartupCheck} refuses
 * to boot with it still in place — mirroring {@code JwtSecretStartupCheck}
 * (#48) — because a webhook secret known to anyone who can read this
 * repository would let anyone forge a "verified" PSP callback.
 */
@ConfigurationProperties(prefix = "app.payments")
public class WebhookProperties {

  /** Shared HMAC secret for mock-PSP webhook signatures. */
  private String webhookSecret;

  public String getWebhookSecret() { return webhookSecret; }
  public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
