package com.toni.marketplace.offer;

import java.time.Instant;

/**
 * Public shape of a price offer (backlog #168): ids and money, no JPA
 * types, no lock version. {@code orderId} is null until the seller
 * accepts — acceptance is the only outcome that creates an order.
 */
public record OfferDto(
    Long id,
    Long itemId,
    Long buyerId,
    Long sellerId,
    Long priceCents,
    OfferStatus status,
    Long orderId,
    Instant expiresAt,
    Instant createdAt) {

  public static OfferDto from(PriceOffer offer) {
    return new OfferDto(
        offer.getId(),
        offer.getItem().getId(),
        offer.getBuyerId(),
        offer.getSellerId(),
        offer.getPriceCents(),
        offer.getStatus(),
        offer.getOrderId(),
        offer.getExpiresAt(),
        offer.getCreatedAt());
  }
}
