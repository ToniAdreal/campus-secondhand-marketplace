package com.toni.marketplace.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/orders/{id}/ship} (backlog #222): the carrier
 * and tracking number the seller records when mailing the order. Both
 * are seller-entered free text — no carrier integration exists, so the
 * values are stored verbatim and shown to the buyer as-is. The size caps
 * mirror the {@code orders} column widths (Flyway V66); blank values are
 * rejected 400 by jakarta validation before the service ever runs.
 */
public record ShipOrderRequest(
    @NotBlank(message = "carrier is required")
    @Size(max = 64, message = "carrier must be at most 64 characters")
    String carrier,
    @NotBlank(message = "tracking number is required")
    @Size(max = 128, message = "tracking number must be at most 128 characters")
    String trackingNumber) {
}
