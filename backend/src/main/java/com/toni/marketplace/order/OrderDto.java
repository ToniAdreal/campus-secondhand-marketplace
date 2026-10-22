package com.toni.marketplace.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Public shape of an order: ids and money, no JPA types, no lock version.
 * {@code captureId} / {@code refundId} are the mock PSP's references for
 * the order (backlog #114) — {@code null} until the corresponding
 * transition (pay / refund) has happened; the internal PSP idempotency
 * keys on the row are never exposed.
 *
 * <p>{@code handoverCode} (backlog #179) is the exception to "every read
 * carries every field": it holds the buyer's 6-digit handover-code
 * plaintext ONLY in the two responses that mint it — the pay response
 * and the regenerate response, both buyer-only surfaces. Every other
 * DTO (buyer reads, seller reads, lists) is built via
 * {@link #from(Order)} with the component {@code null}, and the
 * component-level {@code NON_NULL} include drops the key from those
 * payloads entirely — a seller-facing payload never carries the field,
 * and the stored hash is never exposed anywhere.
 *
 * <p>{@code refundedAmountCents} (backlog #210) is the cumulative amount
 * refunded so far: 0 until the first refund, strictly between 0 and
 * {@code amountCents} while the order is PAID-but-partially-refunded,
 * and exactly {@code amountCents} once the order is REFUNDED — the
 * field the order views render their "partially refunded" badge from.
 */
public record OrderDto(
    Long id,
    Long itemId,
    Long buyerId,
    OrderStatus status,
    Long amountCents,
    Instant createdAt,
    String captureId,
    String refundId,
    long refundedAmountCents,
    @JsonInclude(JsonInclude.Include.NON_NULL) String handoverCode) {

  public static OrderDto from(Order order) {
    return from(order, null);
  }

  /** Builds the DTO carrying a freshly minted handover-code plaintext (buyer-only responses). */
  public static OrderDto from(Order order, String handoverCode) {
    return new OrderDto(
        order.getId(),
        order.getItem().getId(),
        order.getBuyerId(),
        order.getStatus(),
        order.getAmountCents(),
        order.getCreatedAt(),
        order.getCaptureId(),
        order.getRefundId(),
        order.getRefundedAmountCents(),
        handoverCode);
  }
}
