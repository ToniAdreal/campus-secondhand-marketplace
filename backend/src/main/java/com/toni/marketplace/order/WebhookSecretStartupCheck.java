package com.toni.marketplace.order;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the mock-PSP webhook secret on the {@code mysql}
 * profile (backlog #140), mirroring {@code JwtSecretStartupCheck} (#48).
 * The committed dev placeholder would let anyone who can read this
 * repository forge a signature-valid PSP callback, so a mysql-profile
 * boot (the docker-compose path) refuses it. The bean only exists on
 * that profile, so local H2 runs and the default-profile test suite are
 * unaffected.
 */
@Component
@Profile("mysql")
public class WebhookSecretStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

  /**
   * The dev placeholder secret. Must match the default in
   * {@code application.yml}'s {@code app.payments.webhook-secret}; the
   * startup-check test pins the coupling.
   */
  static final String DEV_PLACEHOLDER_SECRET = "dev-only-webhook-secret-change-me";

  private final WebhookProperties properties;

  public WebhookSecretStartupCheck(WebhookProperties properties) {
    this.properties = properties;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (DEV_PLACEHOLDER_SECRET.equals(properties.getWebhookSecret())) {
      throw new IllegalStateException(
          "Refusing to boot on the 'mysql' profile with the committed dev "
              + "webhook secret — anyone could forge a mock-PSP callback. Set the "
              + "APP_PAYMENTS_WEBHOOK_SECRET environment variable to a random "
              + "secret shared only with the PSP.");
    }
  }
}
