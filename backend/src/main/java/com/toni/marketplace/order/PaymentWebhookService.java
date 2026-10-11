package com.toni.marketplace.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mock-PSP webhook receiver logic (backlog #140).
 *
 * <p>Authentication: there is no Bearer token on this route — a real PSP
 * callback cannot hold a user session. Each delivery instead carries an
 * HMAC-SHA256 signature header ({@link #SIGNATURE_HEADER}) over the RAW
 * request body, keyed with the shared secret
 * ({@code app.payments.webhook-secret}). Verification compares digests in
 * constant time and runs BEFORE any parsing or idempotency check, so a
 * tampered replay is a 401, never a 200.
 *
 * <p>Reconciliation contract — a webhook NEVER moves money state by
 * itself:
 * <ul>
 *   <li>{@code capture.succeeded} reconciles only when an order carries
 *       the event's capture id (backlog #114) AND already sits in a
 *       captured state (PAID, COMPLETED or REFUNDED — a refunded order
 *       was still captured).</li>
 *   <li>{@code refund.succeeded} reconciles only when an order carries
 *       the event's refund id AND is already REFUNDED.</li>
 *   <li>Anything else — unknown reference, order in the wrong state,
 *       unknown event type — is a MISMATCH: logged, counted on
 *       {@code payments.webhook.mismatched}, and still answered 200 so
 *       the PSP stops retrying (the synchronous path remains the source
 *       of truth; the mismatch is an operational signal, not an error
 *       the PSP can fix by redelivering).</li>
 * </ul>
 *
 * <p>Idempotency: the delivery is recorded in the {@code webhook_event}
 * ledger (Flyway V28) keyed on the PSP event id inside the same
 * transaction as the reconciliation count. A replay finds its row and
 * returns the stored outcome with {@code duplicate=true} — no second
 * row, no second matched/mismatched count.
 */
@Service
public class PaymentWebhookService {

  /** Signature header the mock PSP signs deliveries with (backlog #140). */
  public static final String SIGNATURE_HEADER = "X-Mock-Psp-Signature";

  public static final String TYPE_CAPTURE_SUCCEEDED = "capture.succeeded";
  public static final String TYPE_REFUND_SUCCEEDED = "refund.succeeded";

  private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);

  /** Acknowledgement returned for every accepted (200) delivery. */
  public record WebhookAck(String eventId, String outcome, boolean duplicate) {}

  private final WebhookEventRepository events;
  private final OrderRepository orders;
  private final WebhookProperties properties;
  private final WebhookMetrics metrics;
  private final ObjectMapper mapper;

  public PaymentWebhookService(WebhookEventRepository events, OrderRepository orders,
                               WebhookProperties properties, WebhookMetrics metrics,
                               ObjectMapper mapper) {
    this.events = events;
    this.orders = orders;
    this.properties = properties;
    this.metrics = metrics;
    this.mapper = mapper;
  }

  /** Hex HMAC-SHA256 of {@code body} under {@code secret} — the wire format. */
  public static String sign(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /** Constant-time check of a presented signature against the raw body. */
  public boolean signatureValid(String presented, byte[] rawBody) {
    String secret = properties.getWebhookSecret();
    if (presented == null || presented.isBlank() || secret == null || secret.isBlank()) {
      return false;
    }
    String expected = sign(secret, rawBody);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        presented.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Applies one signature-verified delivery. The controller has already
   * rejected bad signatures with 401; a malformed payload after a valid
   * signature is the sender's bug and answers 400 in the envelope.
   */
  @Transactional
  public WebhookAck handle(byte[] rawBody) {
    JsonNode root;
    try {
      root = mapper.readTree(rawBody);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed webhook payload");
    }
    String eventId = text(root, "id");
    String type = text(root, "type");
    if (eventId == null || type == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "webhook payload requires id and type");
    }

    var existing = events.findByEventId(eventId);
    if (existing.isPresent()) {
      metrics.replayed();
      log.info("webhook replay ignored: eventId={} type={}", eventId, type);
      return new WebhookAck(eventId, existing.get().getOutcome().name(), true);
    }

    String reference = TYPE_CAPTURE_SUCCEEDED.equals(type)
        ? text(root, "captureId") : text(root, "refundId");
    WebhookEvent.Outcome outcome = reconcile(type, reference);
    try {
      events.saveAndFlush(new WebhookEvent(eventId, type, reference, outcome));
    } catch (DataIntegrityViolationException race) {
      // A concurrent delivery of the same event id won the insert; the
      // unique key on event_id is the final arbiter (the #140 replay
      // contract under races, mirroring the order guard-column pattern).
      metrics.replayed();
      return new WebhookAck(eventId, outcome.name(), true);
    }
    if (outcome == WebhookEvent.Outcome.MATCHED) {
      metrics.matched();
    } else {
      metrics.mismatched();
      log.warn("webhook mismatch: eventId={} type={} reference={}",
          eventId, type, reference);
    }
    return new WebhookAck(eventId, outcome.name(), false);
  }

  private WebhookEvent.Outcome reconcile(String type, String reference) {
    if (reference == null) {
      return WebhookEvent.Outcome.MISMATCH;
    }
    if (TYPE_CAPTURE_SUCCEEDED.equals(type)) {
      return orders.findByCaptureId(reference)
          .filter(o -> o.getStatus() == OrderStatus.PAID
              || o.getStatus() == OrderStatus.COMPLETED
              || o.getStatus() == OrderStatus.REFUNDED)
          .map(o -> WebhookEvent.Outcome.MATCHED)
          .orElse(WebhookEvent.Outcome.MISMATCH);
    }
    if (TYPE_REFUND_SUCCEEDED.equals(type)) {
      return orders.findByRefundId(reference)
          .filter(o -> o.getStatus() == OrderStatus.REFUNDED)
          .map(o -> WebhookEvent.Outcome.MATCHED)
          .orElse(WebhookEvent.Outcome.MISMATCH);
    }
    return WebhookEvent.Outcome.MISMATCH;
  }

  private static String text(JsonNode root, String field) {
    JsonNode node = root == null ? null : root.get(field);
    if (node == null || node.isNull()) {
      // Tolerate the Stripe-shaped nesting ({data:{captureId:...}}) so a
      // sender using either shape reconciles identically.
      JsonNode data = root == null ? null : root.get("data");
      node = data == null ? null : data.get(field);
    }
    if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
      return null;
    }
    return node.asText();
  }
}
