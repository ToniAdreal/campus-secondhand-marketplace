package com.toni.marketplace.order;

import java.time.Instant;

/** Public shape of an order: ids and money, no JPA types, no lock version. */
public record OrderDto(
    Long id,
    Long itemId,
    Long buyerId,
    OrderStatus status,
    Long amountCents,
    Instant createdAt) {

  public static OrderDto from(Order order) {
    return new OrderDto(
        order.getId(),
        order.getItem().getId(),
        order.getBuyerId(),
        order.getStatus(),
        order.getAmountCents(),
        order.getCreatedAt());
  }
}
