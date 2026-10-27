package com.toni.marketplace.order;

/**
 * Body of {@code POST /api/orders/{id}/refund} (backlog #210, reason added
 * by backlog #239). The whole body — and the amount field inside it —
 * is optional: an absent {@code amountCents} means "refund everything
 * still refundable", which is exactly the pre-#210 all-or-nothing
 * behaviour. A present amount is a partial refund of that many cents;
 * the service validates it against the order's remaining refundable
 * balance (non-positive or over-remaining → 422 before any PSP call).
 *
 * <p>The {@code reason} field, by contrast, is required on every direct
 * refund call (blank or absent → 400, over 500 chars → 400): money
 * moving back with no recorded why is exactly the gap #239 closes. The
 * reason is stored on the REFUNDED payment-attempt row written in the
 * refund transaction, and surfaced in the ADMIN attempt viewer and on
 * the order receipt. The idempotent re-refund of an already-REFUNDED
 * order still requires a reason — a malformed request is malformed
 * even when the money has already moved. Dispute-resolved refunds do
 * not use this field; their note is backlog #240.
 */
public record RefundOrderRequest(Long amountCents, String reason) {
}
