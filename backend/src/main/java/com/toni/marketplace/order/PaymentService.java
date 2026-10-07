package com.toni.marketplace.order;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * Mock payment-service-provider (PSP) adapter. There is no real payment
 * processor behind this project: {@link #capture(Order)} and
 * {@link #refund(Order)} perform no network I/O and move no money. They
 * exist to model the PENDING → PAID → REFUNDED order state machine — the
 * exact shape a Stripe/Adyen capture/refund step would take — and to give
 * the pay and refund endpoints a single, testable seam where a real PSP
 * client would later be injected. Captures and refunds are synchronous and
 * always succeed (a declined path is a declared follow-up, see the code
 * comment).
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
   * Result of a (mock) refund: a mock refund id plus the refunded amount.
   * The id has no meaning outside this service — it is not a real payment
   * reference. Mirrors what a real PSP's refund response would carry, so a
   * future real client only needs to fill this record for real.
   */
  public record RefundResult(String refundId, long amountCents) {}

  /**
   * How many refunds this mock has issued, process-wide. The mock is
   * side-effect-free, so the counter is the only observable proof that a
   * double refund did not trigger a second PSP call — the refund endpoint
   * is idempotent and tests pin this counter. A real PSP client would not
   * expose this; it would carry its own provider-side idempotency tracking.
   */
  private final AtomicLong refundsIssued = new AtomicLong();

  public long refundsIssued() {
    return refundsIssued.get();
  }

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

  /**
   * Refunds a captured order (mock PSP — no real money moves). Synchronous by
   * design: the caller transitions the order to REFUNDED in the same
   * transaction, so a refund that "succeeds" can never leave the order PAID.
   * The mock accepts only PAID orders; enforcing that is the caller's job
   * ({@link OrderService#refund(Long, boolean, Long)}) — this seam stays a
   * dumb PSP stand-in, the way a real provider client would be.
   */
  public RefundResult refund(Order order) {
    refundsIssued.incrementAndGet();
    return new RefundResult(
        "rfd_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
        order.getAmountCents());
  }
}
