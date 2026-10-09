package com.toni.marketplace.order;

/**
 * Body of {@code POST /api/orders/{id}/pay} — optional since backlog #92.
 * The whole body, and the {@code paymentToken} field inside it, may be
 * omitted: the mock PSP then uses its default succeeding token.
 *
 * <p>{@code paymentToken} is a mock-only seam, not a real card token: no
 * real payment method exists in this project. The literal value
 * {@link PaymentService#DECLINE_TOKEN} deterministically declines the
 * capture (see {@link PaymentService}); any other value (or none) succeeds.
 * The token is never persisted and never logged.
 */
public record PayRequest(String paymentToken) {
}
