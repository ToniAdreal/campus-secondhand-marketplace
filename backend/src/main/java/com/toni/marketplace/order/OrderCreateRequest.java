package com.toni.marketplace.order;

import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/orders}: which listing to buy. The buyer is the
 * JWT principal, never a request parameter — a client cannot order on someone
 * else's behalf. */
public record OrderCreateRequest(
    @NotNull(message = "itemId is required") Long itemId) {
}
