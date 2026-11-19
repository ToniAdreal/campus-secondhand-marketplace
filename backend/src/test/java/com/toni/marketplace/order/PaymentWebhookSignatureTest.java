package com.toni.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Timestamped webhook signatures (backlog #149): the header is
 * Stripe-style {@code t=<unix seconds>,v1=<hmac over "<t>.<raw body>">}
 * and a delivery is accepted only inside
 * {@code app.webhooks.signature-tolerance} (default 5 min) of the
 * injected clock. These unit tests pin the freshness + format rules
 * against a fixed clock — the MockMvc suite
 * ({@link PaymentWebhookTest}) pins the same rules end-to-end as 401s.
 */
class PaymentWebhookSignatureTest {

  private static final String SECRET = "s3cret";
  private static final Instant NOW = Instant.ofEpochSecond(1_760_000_000L);
  private static final byte[] BODY =
      "{\"id\":\"evt_sig\"}".getBytes(StandardCharsets.UTF_8);

  private PaymentWebhookService service(Duration tolerance) {
    WebhookProperties props = new WebhookProperties();
    props.setWebhookSecret(SECRET);
    WebhookSignatureProperties sigProps = new WebhookSignatureProperties();
    if (tolerance != null) {
      sigProps.setSignatureTolerance(tolerance);
    }
    return new PaymentWebhookService(null, null, props, sigProps,
        WebhookMetrics.noop(), new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String signAt(Instant at) {
    return PaymentWebhookService.sign(SECRET, at.getEpochSecond(), BODY);
  }

  @Test
  void freshDeliveryIsValid() {
    assertThat(service(null).signatureValid(signAt(NOW), BODY)).isTrue();
    assertThat(service(null).signatureValid(signAt(NOW.minusSeconds(299)), BODY)).isTrue();
  }

  @Test
  void staleTimestampIsInvalidEvenWithAValidHmacForThatTimestamp() {
    // 6 minutes old against the default 5-minute tolerance: the HMAC is
    // cryptographically correct for its own timestamp — freshness, not
    // signature validity, is what rejects it.
    assertThat(service(null).signatureValid(signAt(NOW.minusSeconds(360)), BODY)).isFalse();
  }

  @Test
  void farFutureTimestampIsInvalid() {
    assertThat(service(null).signatureValid(signAt(NOW.plusSeconds(3600)), BODY)).isFalse();
  }

  @Test
  void tamperedTimestampIsInvalid() {
    // Keep the v1 from a fresh signing but swap in a different (still
    // fresh) t: the HMAC covers "<t>.<body>", so it no longer verifies.
    String fresh = signAt(NOW);
    String digest = fresh.substring(fresh.indexOf(",v1=") + 4);
    String tampered = "t=" + NOW.minusSeconds(60).getEpochSecond() + ",v1=" + digest;
    assertThat(service(null).signatureValid(tampered, BODY)).isFalse();
  }

  @Test
  void malformedAndLegacyHeadersAreInvalid() {
    PaymentWebhookService svc = service(null);
    assertThat(svc.signatureValid("not-a-signature", BODY)).isFalse();
    assertThat(svc.signatureValid("t=" + NOW.getEpochSecond(), BODY)).isFalse();
    assertThat(svc.signatureValid("v1=abc", BODY)).isFalse();
    assertThat(svc.signatureValid("t=soon,v1=abc", BODY)).isFalse();
    // Any bare-hex presentation (the pre-#149 header shape — a lone hex
    // HMAC with no t= component) gets no dual-format grace.
    String bareHex = PaymentWebhookService.sign(SECRET, NOW.getEpochSecond(), BODY)
        .replaceFirst("t=\\d+,v1=", "");
    assertThat(svc.signatureValid(bareHex, BODY)).isFalse();
    assertThat(svc.signatureValid("t=" + NOW.getEpochSecond() + ",v1=" + bareHex + ",v0=x",
        BODY)).isFalse();
  }

  @Test
  void customToleranceMovesTheCutOff() {
    String tenMinutesOld = signAt(NOW.minusSeconds(600));
    assertThat(service(Duration.ofMinutes(15)).signatureValid(tenMinutesOld, BODY)).isTrue();
    assertThat(service(Duration.ofMinutes(5)).signatureValid(tenMinutesOld, BODY)).isFalse();
  }

  @Test
  void nullAndBlankSecretsAndBodyAreInvalid() {
    // Backlog #403: the fail-closed guard at the top of signatureValid —
    // a null/blank presented header, a null body, or a null/blank shared
    // secret can never verify. Without the secret checks a mutant would
    // HMAC with the empty key and accept a delivery "signed" with it.
    PaymentWebhookService svc = service(null);
    String fresh = signAt(NOW);
    assertThat(svc.signatureValid(null, BODY)).isFalse();
    assertThat(svc.signatureValid("   ", BODY)).isFalse();
    assertThat(svc.signatureValid(fresh, null)).isFalse();

    WebhookProperties blankProps = new WebhookProperties();
    blankProps.setWebhookSecret("   ");
    PaymentWebhookService blankSvc = new PaymentWebhookService(null, null, blankProps,
        new WebhookSignatureProperties(), WebhookMetrics.noop(), new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(blankSvc.signatureValid(
        PaymentWebhookService.sign("   ", NOW.getEpochSecond(), BODY), BODY)).isFalse();

    WebhookProperties nullProps = new WebhookProperties();
    PaymentWebhookService nullSvc = new PaymentWebhookService(null, null, nullProps,
        new WebhookSignatureProperties(), WebhookMetrics.noop(), new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(nullSvc.signatureValid(fresh, BODY)).isFalse();
  }

  @Test
  void nullSignaturePropertiesFallsBackToDefaultTolerance() {
    // Backlog #403: a null WebhookSignatureProperties is not a crash —
    // signatureTolerance() falls back to the 5-minute default.
    WebhookProperties props = new WebhookProperties();
    props.setWebhookSecret(SECRET);
    PaymentWebhookService svc = new PaymentWebhookService(null, null, props, null,
        WebhookMetrics.noop(), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(svc.signatureValid(signAt(NOW), BODY)).isTrue();
    assertThat(svc.signatureValid(signAt(NOW.minusSeconds(360)), BODY)).isFalse();
  }

  @Test
  void nonPositiveToleranceFallsBackToDefault() {
    // Backlog #403: zero or negative configured tolerance is a
    // misconfiguration, not a stricter window — signatureTolerance()
    // falls back to the 5-minute default instead of rejecting every
    // delivery, so a delivery 1 second old still verifies.
    String oneSecondOld = signAt(NOW.minusSeconds(1));
    assertThat(service(Duration.ZERO).signatureValid(oneSecondOld, BODY)).isTrue();
    assertThat(service(Duration.ofMinutes(-1)).signatureValid(oneSecondOld, BODY)).isTrue();
  }
}
