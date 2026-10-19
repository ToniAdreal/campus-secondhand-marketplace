package com.toni.marketplace.offer;

import java.time.Instant;

/**
 * Public shape of a price offer (backlog #168): ids and money, no JPA
 * types, no lock version. {@code orderId} is null until an accept
 * transition creates the order. {@code counterPriceCents} is null until
 * the seller counters (backlog #193) — a COUNTERED offer's agreed price
 * is the counter price, and the order a counter-acceptance creates is
 * priced at it.
 */
public record OfferDto(
    Long id,
    Long itemId,
    Long buyerId,
    Long sellerId,
    Long priceCents,
    Long counterPriceCents,
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
        offer.getCounterPriceCents(),
        offer.getStatus(),
        offer.getOrderId(),
        offer.getExpiresAt(),
        offer.getCreatedAt());
  }
}
