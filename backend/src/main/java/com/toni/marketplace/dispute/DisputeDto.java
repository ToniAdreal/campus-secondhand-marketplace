package com.toni.marketplace.dispute;

import com.toni.marketplace.order.OrderStatus;
import java.time.Instant;

/**
 * Public shape of an order dispute (backlog #169): ids and state, no
 * JPA types, no lock version. {@code previousStatus} is the order
 * status the dispute froze; {@code listingRestored} is null until a
 * refund resolution writes it (see {@link OrderDispute});
 * {@code resolvedAt} is null while the dispute is OPEN.
 */
public record DisputeDto(
    Long id,
    Long orderId,
    Long buyerId,
    Long sellerId,
    String reason,
    DisputeStatus status,
    OrderStatus previousStatus,
    Boolean listingRestored,
    Instant createdAt,
    Instant resolvedAt) {

  public static DisputeDto from(OrderDispute dispute) {
    return new DisputeDto(
        dispute.getId(),
        dispute.getOrderId(),
        dispute.getBuyerId(),
        dispute.getSellerId(),
        dispute.getReason(),
        dispute.getStatus(),
        dispute.getPreviousStatus(),
        dispute.getListingRestored(),
        dispute.getCreatedAt(),
        dispute.getResolvedAt());
  }
}
