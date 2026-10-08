package com.toni.marketplace.order;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * Mock payment-service-provider (PSP) adapter. There is no real payment
 * processor behind this project: {@link #capture(Order, String)} and
 * {@link #refund(Order, String)} perform no network I/O and move no money.
 * They exist to model the PENDING → PAID → REFUNDED order state machine —
 * the exact shape a Stripe/Adyen capture/refund step would take — and to
 * give the pay and refund endpoints a single, testable seam where a real
 * PSP client would later be injected. Captures and refunds are synchronous
 * and always succeed (a declined path is a declared follow-up, see the code
 * comment).
 *
 * <p>Idempotency model (backlog #65): every capture and refund takes an
 * order-scoped idempotency key, exactly the way a real PSP client would
 * pass an {@code Idempotency-Key} header (Stripe) on the capture/refund
 * call. The mock remembers each key it has seen and replays the stored
 * result when the key repeats, so a retry after a crash between the PSP
 * call and the order-row commit can never produce a second logical capture
 * or refund. Capture keys and refund keys live in separate namespaces, so
 * a capture key can never collide with a refund key.
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
   * How many logical captures/refunds this mock has issued, process-wide.
   * The mock is side-effect-free, so the counters are the only observable
   * proof that a repeated idempotency key never triggered a second PSP
   * call — tests pin the single-invocation guarantees. A real PSP client
   * would not expose this; it would carry its own provider-side idempotency
   * tracking.
   */
  private final AtomicLong capturesIssued = new AtomicLong();
  private final AtomicLong refundsIssued = new AtomicLong();

  public long capturesIssued() {
    return capturesIssued.get();
  }

  public long refundsIssued() {
    return refundsIssued.get();
  }

  /**
   * Prior results keyed by the caller's idempotency key. A repeated key
   * replays the stored result instead of issuing a second capture — the
   * mechanism that makes crash-between-capture-and-commit retries safe.
   */
  private final ConcurrentHashMap<String, CaptureResult> capturesByKey =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RefundResult> refundsByKey =
      new ConcurrentHashMap<>();

  /**
   * Captures the order amount against the (mock) buyer payment method.
   * Synchronous by design: the caller transitions the order to PAID in the
   * same transaction, so a capture that "succeeds" can never leave the
   * order unpaid. Declined/failed captures do not exist in the mock — a
   * declined path (and its 402 mapping) is a declared follow-up if a real
   * PSP is ever wired in.
   *
   * <p>The key is generated once per order by the caller (see
   * {@link OrderService#pay}) and stored on the order row: repeating a
   * capture with the same key returns the original result without issuing
   * a second logical capture.
   */
  public CaptureResult capture(Order order, String idempotencyKey) {
    requireKey(idempotencyKey);
    return capturesByKey.computeIfAbsent(idempotencyKey, key -> {
      capturesIssued.incrementAndGet();
      return new CaptureResult(
          "cap_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
          order.getAmountCents());
    });
  }

  /**
   * Refunds a captured order (mock PSP — no real money moves). Synchronous
   * by design: the caller transitions the order to REFUNDED in the same
   * transaction, so a refund that "succeeds" can never leave the order
   * PAID. The mock accepts only PAID orders; enforcing that is the caller's
   * job ({@link OrderService#refund(Long, boolean, Long)}) — this seam stays
   * a dumb PSP stand-in, the way a real provider client would be.
   *
   * <p>Repeating a refund with the same key replays the original result
   * without issuing a second logical refund.
   */
  public RefundResult refund(Order order, String idempotencyKey) {
    requireKey(idempotencyKey);
    return refundsByKey.computeIfAbsent(idempotencyKey, key -> {
      refundsIssued.incrementAndGet();
      return new RefundResult(
          "rfd_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
          order.getAmountCents());
    });
  }

  private static void requireKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "PSP idempotency key must not be blank");
    }
  }
}
