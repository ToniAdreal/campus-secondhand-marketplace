package com.toni.marketplace.order;

import java.time.Instant;

/**
 * Public shape of an order: ids and money, no JPA types, no lock version.
 * {@code captureId} / {@code refundId} are the mock PSP's references for
 * the order (backlog #114) — {@code null} until the corresponding
 * transition (pay / refund) has happened; the internal PSP idempotency
 * keys on the row are never exposed.
 */
public record OrderDto(
    Long id,
    Long itemId,
    Long buyerId,
    OrderStatus status,
    Long amountCents,
    Instant createdAt,
    String captureId,
    String refundId) {

  public static OrderDto from(Order order) {
    return new OrderDto(
        order.getId(),
        order.getItem().getId(),
        order.getBuyerId(),
        order.getStatus(),
        order.getAmountCents(),
        order.getCreatedAt(),
        order.getCaptureId(),
        order.getRefundId());
  }
}
