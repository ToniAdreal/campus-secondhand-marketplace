package com.toni.marketplace.order;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional writer/reader for the payment-attempt ledger
 * (backlog #211), split out of {@link OrderService} on purpose — the
 * {@link WebhookEventRecorder} pattern (backlog #161). The two write
 * paths deliberately run under different propagations, because the two
 * outcomes have opposite durability requirements:
 *
 * <ul>
 *   <li>{@link #recordDeclined} runs {@code REQUIRES_NEW}: a decline is
 *       reported by throwing {@code PaymentDeclinedException} out of
 *       {@code OrderService.pay}, which rolls the pay transaction back —
 *       a DECLINED row written inside that transaction would be erased
 *       by its own rejection. In its own committed transaction the row
 *       survives, exactly like {@link HandoverAttemptService}'s failure
 *       counts.</li>
 *   <li>{@link #recordSucceeded} joins the caller's transaction
 *       (default {@code REQUIRED} propagation): the row is evidence the
 *       capture committed, so if the pay transaction later rolls back —
 *       the deliberate in-transaction flush surfacing a 409, or any
 *       later failure — the SUCCEEDED row rolls back with it and never
 *       claims a success that did not happen.</li>
 * </ul>
 *
 * <p>The payment token is never passed to this class: a row records the
 * order, the outcome and the order-scoped PSP idempotency key only.
 */
@Service
public class PaymentAttemptRecorder {

  private final PaymentAttemptRepository attempts;

  public PaymentAttemptRecorder(PaymentAttemptRepository attempts) {
    this.attempts = attempts;
  }

  /**
   * Appends a DECLINED row in its own transaction, flushed before that
   * transaction commits, so the row is durable by the time the caller's
   * rollback runs.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentAttempt recordDeclined(long orderId, String idempotencyKey) {
    return attempts.saveAndFlush(
        new PaymentAttempt(orderId, PaymentAttempt.Outcome.DECLINED, idempotencyKey));
  }

  /**
   * Appends a SUCCEEDED row inside the caller's capture transaction —
   * it commits if and only if the pay commits.
   */
  @Transactional
  public PaymentAttempt recordSucceeded(long orderId, String idempotencyKey) {
    return attempts.save(
        new PaymentAttempt(orderId, PaymentAttempt.Outcome.SUCCEEDED, idempotencyKey));
  }

  /**
   * Appends a REFUNDED row inside the caller's refund transaction
   * (backlog #239) — same durability rule as {@link #recordSucceeded}:
   * it commits if and only if the refund commits, so a 409 race loser
   * (whose flush throws before this call) leaves no refund claim
   * behind. The reason is already validated (required, max 500 chars)
   * by {@code OrderService.refund} before this point.
   */
  @Transactional
  public PaymentAttempt recordRefunded(long orderId, String refundKey, String reason) {
    return attempts.save(
        new PaymentAttempt(orderId, PaymentAttempt.Outcome.REFUNDED, refundKey, reason));
  }

  /** The most recent direct-refund row against one order, if any. */
  @Transactional(readOnly = true)
  public Optional<PaymentAttempt> findLatestRefunded(long orderId) {
    return attempts.findFirstByOrderIdAndOutcomeOrderByCreatedAtDescIdDesc(
        orderId, PaymentAttempt.Outcome.REFUNDED);
  }

  /** The most recent attempt against one order, if any. */
  @Transactional(readOnly = true)
  public Optional<PaymentAttempt> findLatest(long orderId) {
    return attempts.findFirstByOrderIdOrderByCreatedAtDescIdDesc(orderId);
  }

  /**
   * ADMIN payment-attempt viewer (backlog #211): paginated, newest first
   * (the controller's {@code @PageableDefault} supplies createdAt/id
   * DESC; the global max-page-size cap from #75 clamps oversized
   * {@code size}). The outcome filter is an optional exact match bound
   * to the {@link PaymentAttempt.Outcome} enum, so only allowlisted
   * values reach the query. Rows map to {@link PaymentAttemptDto} —
   * never the entity. Mirrors
   * {@link PaymentWebhookService#listWebhookEvents}.
   */
  @Transactional(readOnly = true)
  public Page<PaymentAttemptDto> listAttempts(PaymentAttempt.Outcome outcome,
                                              Pageable pageable) {
    return attempts.searchAttempts(outcome, pageable).map(PaymentAttemptDto::of);
  }
}
