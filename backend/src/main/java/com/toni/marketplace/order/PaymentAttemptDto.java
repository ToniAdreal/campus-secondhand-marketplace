package com.toni.marketplace.order;

import java.time.Instant;

/**
 * One row of the ADMIN payment-attempt viewer (backlog #211). Built from
 * the entity deliberately instead of serializing {@link PaymentAttempt}
 * directly — the same projection rule {@link WebhookEventDto} follows —
 * so the wire shape stays stable even if the ledger grows internal
 * columns.
 *
 * <p>What is exposed: the row id, the order the attempt ran against, the
 * {@link PaymentAttempt.Outcome}, the order-scoped PSP idempotency key
 * the attempt ran under (an internal correlation id, backlog #65) and
 * the attempt instant. What is NOT exposed, by design: the buyer's
 * payment token — the ledger never stores it, so there is nothing of it
 * to leak here.
 */
public record PaymentAttemptDto(long id, long orderId, PaymentAttempt.Outcome outcome,
                                String idempotencyKey, String refundReason, Instant createdAt) {

  public static PaymentAttemptDto of(PaymentAttempt row) {
    return new PaymentAttemptDto(row.getId(), row.getOrderId(), row.getOutcome(),
        row.getIdempotencyKey(), row.getRefundReason(), row.getCreatedAt());
  }
}
