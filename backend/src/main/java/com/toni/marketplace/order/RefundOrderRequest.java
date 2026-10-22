package com.toni.marketplace.order;

/**
 * Body of {@code POST /api/orders/{id}/refund} (backlog #210). The whole
 * body — and the field inside it — is optional: an absent
 * {@code amountCents} means "refund everything still refundable", which
 * is exactly the pre-#210 all-or-nothing behaviour. A present amount is
 * a partial refund of that many cents; the service validates it against
 * the order's remaining refundable balance (non-positive or
 * over-remaining → 422 before any PSP call).
 */
public record RefundOrderRequest(Long amountCents) {
}
