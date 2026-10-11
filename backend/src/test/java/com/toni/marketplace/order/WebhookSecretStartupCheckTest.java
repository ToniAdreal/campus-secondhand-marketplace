package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/**
 * Backlog #140: fail fast when the committed dev webhook secret is used
 * with the {@code mysql} profile — mirroring JwtSecretStartupCheckTest.
 * The placeholder constant is also the secret the MockMvc suite signs
 * with, which pins the constant to the application.yml default.
 */
class WebhookSecretStartupCheckTest {

  @Test
  void checkIsRegisteredOnlyOnTheMysqlProfile() {
    assertThat(WebhookSecretStartupCheck.class.getAnnotation(Profile.class).value())
        .containsExactly("mysql");
  }

  @Test
  void placeholderSecretThrowsWithActionableMessage() {
    WebhookProperties props = new WebhookProperties();
    props.setWebhookSecret(WebhookSecretStartupCheck.DEV_PLACEHOLDER_SECRET);
    Throwable thrown = catchThrowable(
        () -> new WebhookSecretStartupCheck(props).onApplicationEvent(null));
    assertThat(thrown).isInstanceOf(IllegalStateException.class);
    assertThat(thrown.getMessage()).contains("APP_PAYMENTS_WEBHOOK_SECRET");
  }

  @Test
  void realSecretBoots() {
    WebhookProperties props = new WebhookProperties();
    props.setWebhookSecret("a-real-random-webhook-secret");
    Throwable thrown = catchThrowable(
        () -> new WebhookSecretStartupCheck(props).onApplicationEvent(null));
    assertThat(thrown).isNull();
  }

  @Test
  void signatureRoundTripAndTamperDetection() {
    byte[] body = "{\"id\":\"evt_1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sig = PaymentWebhookService.sign("s3cret", body);
    WebhookProperties props = new WebhookProperties();
    props.setWebhookSecret("s3cret");
    PaymentWebhookService svc = new PaymentWebhookService(
        null, null, props, WebhookMetrics.noop(), new com.fasterxml.jackson.databind.ObjectMapper());
    assertThat(svc.signatureValid(sig, body)).isTrue();
    assertThat(svc.signatureValid(sig,
        "{\"id\":\"evt_2\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
    assertThat(svc.signatureValid(null, body)).isFalse();
  }
}
