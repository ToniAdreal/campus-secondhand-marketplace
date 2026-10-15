package com.toni.marketplace.order;

import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of the mock-PSP webhook receiver (backlog
 * #161), split out of {@link PaymentWebhookService} on purpose — the
 * exact pattern {@link OrderService} + {@link IdempotencyKeyService}
 * already use for the order idempotency key.
 *
 * <p>Every method runs in its own {@code REQUIRES_NEW} transaction, so
 * no step shares a transaction with the caller:
 *
 * <ul>
 *   <li>{@link #record} is the race arbiter: two concurrent deliveries
 *       of one event id both miss the ledger lookup and both reconcile;
 *       exactly one insert commits. The loser's insert fails on the
 *       Flyway V28 {@code uq_webhook_event_event_id} unique key, its
 *       transaction rolls back harmlessly, and the caller — OUTSIDE
 *       that failed transaction — re-reads the winner's committed row
 *       via {@link #findRecorded} and answers the deduped ack.</li>
 *   <li>The split is load-bearing, not cosmetic: the pre-#161 code
 *       caught the duplicate-key exception INSIDE the same
 *       {@code @Transactional} method that had flushed the insert.
 *       The repository proxy had already marked that transaction
 *       rollback-only, so the loser's commit surfaced as an
 *       {@code UnexpectedRollbackException} and the delivery answered
 *       500 instead of the deduped 200 (reproduced with a
 *       latch-released two-thread race, and pinned ever since by
 *       {@code PaymentWebhookConcurrencyTest} on H2 and
 *       {@code PaymentWebhookMySqlIT} on real MySQL 8).</li>
 * </ul>
 */
@Service
public class WebhookEventRecorder {

  private final WebhookEventRepository events;
  private final OrderRepository orders;

  public WebhookEventRecorder(WebhookEventRepository events, OrderRepository orders) {
    this.events = events;
    this.orders = orders;
  }

  /** The already-recorded delivery for {@code eventId}, if any. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<WebhookEvent> findRecorded(String eventId) {
    return events.findByEventId(eventId);
  }

  /**
   * The reconciliation verdict for one delivery (the contract lives in
   * {@link PaymentWebhookService}'s javadoc): a webhook never moves
   * money state, so this only checks whether the PSP reference names
   * an order already in the matching state.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public WebhookEvent.Outcome reconcile(String type, String reference) {
    if (reference == null) {
      return WebhookEvent.Outcome.MISMATCH;
    }
    if (PaymentWebhookService.TYPE_CAPTURE_SUCCEEDED.equals(type)) {
      return orders.findByCaptureId(reference)
          .filter(o -> o.getStatus() == OrderStatus.PAID
              || o.getStatus() == OrderStatus.COMPLETED
              || o.getStatus() == OrderStatus.REFUNDED)
          .map(o -> WebhookEvent.Outcome.MATCHED)
          .orElse(WebhookEvent.Outcome.MISMATCH);
    }
    if (PaymentWebhookService.TYPE_REFUND_SUCCEEDED.equals(type)) {
      return orders.findByRefundId(reference)
          .filter(o -> o.getStatus() == OrderStatus.REFUNDED)
          .map(o -> WebhookEvent.Outcome.MATCHED)
          .orElse(WebhookEvent.Outcome.MISMATCH);
    }
    return WebhookEvent.Outcome.MISMATCH;
  }

  /**
   * Appends the delivery to the webhook_event ledger, flushed before
   * this transaction commits so a concurrent duplicate fails HERE —
   * inside this throwaway transaction — with a
   * {@code DataIntegrityViolationException} the caller can catch and
   * answer from a fresh read, never as a rollback-only 500.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WebhookEvent record(String eventId, String type, String reference,
                             WebhookEvent.Outcome outcome) {
    return events.saveAndFlush(new WebhookEvent(eventId, type, reference, outcome));
  }

  /** One ledger row by its surrogate id (ADMIN replay, backlog #170). */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<WebhookEvent> findById(long id) {
    return events.findById(id);
  }

  /**
   * Counts one ADMIN replay attempt on the row (backlog #170), in its
   * own transaction on purpose: the attempt is evidence and must commit
   * even when the replay itself is then refused (the freshness re-check
   * answers 401) — recording it inside the caller's transaction would
   * let that refusal roll the count back with everything else.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WebhookEvent noteReplay(long id, Instant at) {
    WebhookEvent row = events.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "webhook event not found"));
    row.recordReplay(at);
    return events.saveAndFlush(row);
  }

  /**
   * Stores the verdict of a replay that applied the event (backlog
   * #170): the row's outcome flips to the reconciled value so the next
   * replay takes the deduped no-op path. Same {@code REQUIRES_NEW}
   * discipline as {@link #record} — the flip commits on its own.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WebhookEvent applyOutcome(long id, WebhookEvent.Outcome outcome) {
    WebhookEvent row = events.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "webhook event not found"));
    row.applyOutcome(outcome);
    return events.saveAndFlush(row);
  }
}
