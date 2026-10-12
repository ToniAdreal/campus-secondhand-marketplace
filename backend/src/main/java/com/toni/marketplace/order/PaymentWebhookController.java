package com.toni.marketplace.order;

import com.toni.marketplace.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock-PSP webhook receiver (backlog #140): POST
 * /api/payments/webhooks/mock-psp. Public in {@code SecurityConfig} (no
 * Bearer token — a PSP callback holds no user session) and authenticated
 * instead by the timestamped HMAC-SHA256 signature header (backlog #149:
 * {@code t=<unix seconds>,v1=<hmac over "<t>.<raw body>">}, freshness
 * window {@code app.webhooks.signature-tolerance}), verified in
 * {@link PaymentWebhookService} before anything is parsed or recorded.
 * A missing, malformed, stale or invalid signature answers the JSON 401 envelope; every
 * accepted delivery answers 200 in the envelope — matched, mismatched
 * (a webhook never moves money state by itself) and replayed alike, so
 * the PSP stops retrying.
 */
@RestController
@RequestMapping("/api/payments/webhooks")
@io.swagger.v3.oas.annotations.tags.Tag(
    name = "payments", description = "Mock-PSP webhook receiver (HMAC-signed, no Bearer token)")
public class PaymentWebhookController {

  private final PaymentWebhookService webhooks;

  public PaymentWebhookController(PaymentWebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @PostMapping("/mock-psp")
  public ResponseEntity<ApiResponse<PaymentWebhookService.WebhookAck>> mockPsp(
      @RequestHeader(value = PaymentWebhookService.SIGNATURE_HEADER, required = false)
      String signature,
      @RequestBody byte[] rawBody) {
    if (!webhooks.signatureValid(signature, rawBody)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(ApiResponse.fail(HttpStatus.UNAUTHORIZED.value(), "invalid webhook signature"));
    }
    return ResponseEntity.ok(ApiResponse.ok(webhooks.handle(rawBody)));
  }
}
