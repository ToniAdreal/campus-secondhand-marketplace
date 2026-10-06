package com.toni.marketplace.order;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Mock payment-service-provider (PSP) adapter. There is no real payment
 * processor behind this project: {@link #capture(Order)} performs no network
 * I/O and moves no money. It exists to model the PENDING → PAID order state
 * machine — the exact shape a Stripe/Adyen capture step would take — and to
 * give the pay endpoint a single, testable seam where a real PSP client would
 * later be injected. A capture is synchronous and always succeeds (a declined
 * path is a declared follow-up, see the code comment).
 */
@Service
public class PaymentService {

  /**
   * Result of a (mock) capture: a mock capture id plus the captured amount.
   * The id has no meaning outside this service — it is not a real payment
   * reference.
   */
  public record CaptureResult(String captureId, long amountCents) {}

  /**
   * Captures the order amount against the (mock) buyer payment method.
   * Synchronous by design: the caller transitions the order to PAID in the
   * same transaction, so a capture that "succeeds" can never leave the order
   * unpaid. Declined/failed captures do not exist in the mock — a declined
   * path (and its 402 mapping) is a declared follow-up if a real PSP is
   * ever wired in.
   */
  public CaptureResult capture(Order order) {
    return new CaptureResult(
        "cap_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        order.getAmountCents());
  }
}
