package com.toni.marketplace.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mock-PSP webhook receiver logic (backlog #140).
 *
 * <p>Authentication: there is no Bearer token on this route — a real PSP
 * callback cannot hold a user session. Each delivery instead carries a
 * Stripe-style signature header ({@link #SIGNATURE_HEADER}:
 * {@code t=<unix seconds>,v1=<hex hmac>}, backlog #149) where the
 * HMAC-SHA256 is computed over {@code "<t>.<raw body>"} keyed with the
 * shared secret ({@code app.payments.webhook-secret}). Verification
 * compares digests in constant time and runs BEFORE any parsing or
 * idempotency check, so a tampered replay is a 401, never a 200. The
 * timestamp must also be fresh: a delivery whose {@code t} differs from
 * the injected {@link Clock} by more than
 * {@code app.webhooks.signature-tolerance} (default 5 min, stale or
 * far-future alike) answers the same 401 as a bad signature — a captured
 * delivery now expires, instead of replay defence resting entirely on
 * the event-id ledger. There is NO dual-format grace: the pre-#149 bare
 * hex digest over the body alone is rejected like any other malformed
 * header (the mock PSP is this repo's own sender and signs the new
 * format only).
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
 * ledger (Flyway V28) keyed on the PSP event id. {@link #handle} itself
 * is deliberately NOT transactional — it orchestrates
 * {@link WebhookEventRecorder}, whose lookup, reconcile and record
 * steps each run in their own {@code REQUIRES_NEW} transaction
 * (backlog #161, the {@link IdempotencyKeyService} pattern). A replay
 * finds its row and returns the stored outcome with
 * {@code duplicate=true} — no second row, no second
 * matched/mismatched count. Two deliveries racing on one event id are
 * arbitrated by the ledger's unique key: the loser's insert fails
 * inside its own throwaway transaction, the failure is caught HERE,
 * outside that transaction, and the winner's committed row is
 * re-read for the deduped answer — catching the duplicate-key error
 * inside the inserting transaction instead (the pre-#161 shape)
 * surfaced as a rollback-only 500, never as this 200.
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
  private final WebhookEventRecorder recorder;
  private final WebhookProperties properties;
  private final WebhookSignatureProperties signatureProperties;
  private final WebhookMetrics metrics;
  private final ObjectMapper mapper;
  private final Clock clock;

  public PaymentWebhookService(WebhookEventRepository events, WebhookEventRecorder recorder,
                               WebhookProperties properties,
                               WebhookSignatureProperties signatureProperties,
                               WebhookMetrics metrics,
                               ObjectMapper mapper, Clock clock) {
    this.events = events;
    this.recorder = recorder;
    this.properties = properties;
    this.signatureProperties = signatureProperties;
    this.metrics = metrics;
    this.mapper = mapper;
    this.clock = clock != null ? clock : Clock.systemUTC();
  }

  /**
   * The full {@link #SIGNATURE_HEADER} value for one delivery (backlog
   * #149): {@code t=<unix seconds>,v1=<hex HMAC-SHA256>} where the HMAC
   * covers {@code "<t>.<raw body>"} — binding the timestamp into the
   * signed payload is what makes a tampered {@code t} unverifiable.
   */
  public static String sign(String secret, long timestampSeconds, byte[] body) {
    String prefix = Long.toString(timestampSeconds) + ".";
    byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
    byte[] payload = new byte[prefixBytes.length + body.length];
    System.arraycopy(prefixBytes, 0, payload, 0, prefixBytes.length);
    System.arraycopy(body, 0, payload, prefixBytes.length, body.length);
    return "t=" + timestampSeconds + ",v1=" + hmacHex(secret, payload);
  }

  private static String hmacHex(String secret, byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /**
   * Full header check (backlog #149): the header must parse as exactly
   * {@code t=<unix seconds>,v1=<hex>} (any order, no other keys, no
   * duplicates), the timestamp must sit inside the configured freshness
   * window against the injected clock, and the {@code v1} digest must
   * match in constant time. Every failure — missing, malformed, stale,
   * far-future, tampered, wrong secret, pre-#149 bare-hex format — is
   * the same {@code false}; the controller maps them all to one 401
   * envelope so the failure mode leaks nothing about which check died.
   */
  public boolean signatureValid(String presented, byte[] rawBody) {
    String secret = properties.getWebhookSecret();
    if (presented == null || presented.isBlank() || secret == null || secret.isBlank()
        || rawBody == null) {
      return false;
    }
    String timestampText = null;
    String digest = null;
    for (String part : presented.split(",")) {
      String[] kv = part.trim().split("=", 2);
      if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
        return false;
      }
      switch (kv[0].trim()) {
        case "t" -> {
          if (timestampText != null) {
            return false;
          }
          timestampText = kv[1].trim();
        }
        case "v1" -> {
          if (digest != null) {
            return false;
          }
          digest = kv[1].trim();
        }
        default -> {
          return false;
        }
      }
    }
    if (timestampText == null || digest == null) {
      return false;
    }
    long timestamp;
    try {
      timestamp = Long.parseLong(timestampText);
    } catch (NumberFormatException e) {
      return false;
    }
    if (timestamp <= 0) {
      return false;
    }
    Duration tolerance = signatureProperties == null
        ? Duration.ofMinutes(5) : signatureProperties.getSignatureTolerance();
    if (tolerance == null || tolerance.isNegative() || tolerance.isZero()) {
      tolerance = Duration.ofMinutes(5);
    }
    Duration skew = Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs();
    if (skew.compareTo(tolerance) > 0) {
      return false;
    }
    String expected = sign(secret, timestamp, rawBody);
    String expectedDigest = expected.substring(expected.indexOf(",v1=") + 4);
    return MessageDigest.isEqual(
        expectedDigest.getBytes(StandardCharsets.UTF_8),
        digest.toLowerCase().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Applies one signature-verified delivery. The controller has already
   * rejected bad signatures with 401; a malformed payload after a valid
   * signature is the sender's bug and answers 400 in the envelope.
   *
   * <p>Deliberately NOT {@code @Transactional}: the ledger work runs
   * through {@link WebhookEventRecorder}'s {@code REQUIRES_NEW}
   * transactions so the duplicate-key failure of a lost insert race is
   * caught here — outside the transaction that failed — and answered
   * by re-reading the winner's committed row (backlog #161; see the
   * class javadoc's idempotency paragraph for why the in-transaction
   * catch answered 500 instead).
   */
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

    var existing = recorder.findRecorded(eventId);
    if (existing.isPresent()) {
      metrics.replayed();
      log.info("webhook replay ignored: eventId={} type={}", eventId, type);
      return new WebhookAck(eventId, existing.get().getOutcome().name(), true);
    }

    String reference = TYPE_CAPTURE_SUCCEEDED.equals(type)
        ? text(root, "captureId") : text(root, "refundId");
    WebhookEvent.Outcome outcome = recorder.reconcile(type, reference);
    try {
      recorder.record(eventId, type, reference, outcome);
    } catch (DataIntegrityViolationException race) {
      // A concurrent delivery of the same event id won the insert; the
      // unique key on event_id is the final arbiter. The winner's row
      // is committed by the time the loser's insert fails against it,
      // so the deduped answer replays the STORED outcome — and because
      // the failed insert ran in the recorder's own transaction, this
      // catch is not sitting inside a rollback-only transaction (the
      // pre-#161 shape, which answered this race with a 500).
      metrics.replayed();
      String stored = recorder.findRecorded(eventId)
          .map(row -> row.getOutcome().name())
          .orElse(outcome.name());
      return new WebhookAck(eventId, stored, true);
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

  /**
   * ADMIN webhook-event viewer (backlog #156): paginated, newest first
   * (the controller's {@code @PageableDefault} supplies createdAt/id
   * DESC; the global max-page-size cap from #75 clamps oversized
   * {@code size}). The outcome filter is an optional exact match bound
   * to the {@link WebhookEvent.Outcome} enum, so only allowlisted values
   * reach the query. Rows map to {@link WebhookEventDto} — never the
   * entity, and the ledger stores no raw body or signature to expose.
   */
  @Transactional(readOnly = true)
  public Page<WebhookEventDto> listWebhookEvents(WebhookEvent.Outcome outcome,
                                                 Pageable pageable) {
    return events.searchWebhookEvents(outcome, pageable).map(WebhookEventDto::of);
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
