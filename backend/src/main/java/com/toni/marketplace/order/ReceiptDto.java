package com.toni.marketplace.order;

import java.time.Instant;

/**
 * Printable order receipt (backlog #180): the one document tying an
 * order, its listing, its amount, both parties and the mock PSP
 * references together. Built field-by-field in
 * {@link OrderReceiptService} — never by serialising the {@link Order}
 * entity — and a test pins this exact component set, so no internal
 * field (the PSP idempotency keys, the handover-code hash, the lock
 * counters) can leak into a receipt by accident.
 *
 * <p>Honesty notes:
 * <ul>
 *   <li>{@code paid} is the explicit unpaid marker: a PENDING order's
 *       receipt has {@code paid=false}, {@code paidAt=null} and
 *       {@code captureId=null}.</li>
 *   <li>{@code paidAt}/{@code completedAt} are the real transition
 *       instants (Flyway V44, stamped by the pay/complete transitions).
 *       Orders transitioned before V44 keep {@code null} there — never
 *       backfilled from {@code updatedAt}.</li>
 *   <li>{@code captureId}/{@code refundId} are the mock PSP's demo
 *       references (#114), not a real processor receipt.</li>
 *   <li>{@code refundReason} is the reason from the latest direct
 *       refund's REFUNDED payment-attempt row (#239); null when the
 *       order was never directly refunded — including dispute-resolved
 *       refunds, whose note is backlog #240, not this field.</li>
 *   <li>{@code refundedAmountCents} (backlog #257) is the cumulative
 *       amount refunded so far ({@code Order.refundedAmountCents},
 *       #210): null while nothing has been refunded, the partial total
 *       while a PAID order is partly refunded, exactly
 *       {@code amountCents} once fully refunded — so a partially- or
 *       fully-refunded receipt no longer reads like a full-price paid
 *       receipt.</li>
 *   <li>{@code disputeResolutionNote} (backlog #257) is the ADMIN's
 *       resolution note from the order's RESOLVED_REFUND dispute row
 *       (#240); null when the order has no such dispute — including
 *       OPEN disputes and RESOLVED_RELEASE resolutions, whose note
 *       explains a release, not a refund, and must never surface as a
 *       refund note.</li>
 *   <li>{@code formattedPrice} is derived from {@code amountCents}
 *       server-side so the printed page and the API can never disagree
 *       about the amount.</li>
 * </ul>
 */
public record ReceiptDto(
    Long orderId,
    OrderStatus status,
    boolean paid,
    Long itemId,
    String itemTitle,
    Long amountCents,
    String formattedPrice,
    String buyerUsername,
    String sellerUsername,
    Instant createdAt,
    Instant paidAt,
    Instant completedAt,
    String captureId,
    String refundId,
    String refundReason,
    Long refundedAmountCents,
    String disputeResolutionNote) {

  /** Formats cents as the SPA does: {@code ¥} + yuan with two decimals. */
  static String formatPrice(long amountCents) {
    long abs = Math.abs(amountCents);
    String sign = amountCents < 0 ? "-" : "";
    return sign + "¥" + (abs / 100) + "." + String.format("%02d", abs % 100);
  }
}
