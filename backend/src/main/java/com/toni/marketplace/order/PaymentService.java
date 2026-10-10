package com.toni.marketplace.order;

import com.toni.marketplace.common.PaymentDeclinedException;
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
 * PSP client would later be injected. Captures and refunds are synchronous.
 * A capture declines deterministically when the caller passes the
 * documented mock-only test token {@link #DECLINE_TOKEN} (backlog #92) —
 * the seam a real PSP's decline response would occupy; refunds always
 * succeed in the mock.
 *
 * <p>Idempotency model (backlog #65): every capture and refund takes an
 * order-scoped idempotency key, exactly the way a real PSP client would
 * pass an {@code Idempotency-Key} header (Stripe) on the capture/refund
 * call. The mock remembers each key it has seen and replays the stored
 * result when the key repeats, so a retry after a crash between the PSP
 * call and the order-row commit can never produce a second logical capture
 * or refund. Capture keys and refund keys live in separate namespaces, so
 * a capture key can never collide with a refund key.
 *
 * <p>Replay-memory bound (backlog #124): the replay maps are NOT a
 * permanent ledger. They only need to bridge the window between the PSP
 * call and the order-row commit inside one process: once the caller has
 * flushed the transition, the durable capture/refund id lives on the
 * order row (backlog #114) and every later replay returns from that row
 * before ever reaching this seam. The caller therefore calls
 * {@link #forgetCapture(String)} / {@link #forgetRefund(String)} after a
 * successful flush, so a long-lived JVM does not accumulate one map entry
 * per paid/refunded order for the process lifetime.
 */
@Service
public class PaymentService {

  /**
   * Mock-only decline seam (backlog #92): a capture attempted with this
   * payment token is declined, deterministically, so tests and demos can
   * exercise the decline path without magic global state. It models the
   * test-card numbers real PSPs publish (e.g. Stripe's
   * {@code 4000 0000 0000 0002}); it is not a real payment credential, is
   * never persisted, and any other token value (or none) succeeds.
   */
  public static final String DECLINE_TOKEN = "tok_decline";

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
   * Captures the order amount against the (mock) buyer payment method,
   * using the default succeeding token — see
   * {@link #capture(Order, String, String)}.
   */
  public CaptureResult capture(Order order, String idempotencyKey) {
    return capture(order, idempotencyKey, null);
  }

  /**
   * Captures the order amount against the (mock) buyer payment method.
   * Synchronous by design: the caller transitions the order to PAID in the
   * same transaction, so a capture that "succeeds" can never leave the
   * order unpaid.
   *
   * <p>Decline rule (backlog #92): when {@code paymentToken} is the
   * literal {@link #DECLINE_TOKEN}, the capture is declined and
   * {@link PaymentDeclinedException} is thrown <em>before</em> anything is
   * remembered — the idempotency key is not stored and
   * {@link #capturesIssued()} does not move, so a retry with a succeeding
   * token under the same key can still capture (pinned by tests). A null
   * or blank token, and any other value, succeeds.
   *
   * <p>The key is generated once per order by the caller (see
   * {@link OrderService#pay}) and stored on the order row: repeating a
   * capture with the same key returns the original result without issuing
   * a second logical capture.
   */
  public CaptureResult capture(Order order, String idempotencyKey, String paymentToken) {
    requireKey(idempotencyKey);
    if (DECLINE_TOKEN.equals(paymentToken)) {
      throw new PaymentDeclinedException();
    }
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

  /**
   * Drops the remembered capture result for {@code idempotencyKey}
   * (backlog #124). Called by {@code OrderService.pay} after its
   * deliberate in-transaction flush succeeds: from that point the
   * capture id is durable on the order row, an idempotent re-pay returns
   * from the row's PAID status before reaching this seam, and the replay
   * entry has no remaining job. Keyed and idempotent — forgetting an
   * absent key is a no-op — so a duplicate call can never disturb another
   * order's entry.
   */
  public void forgetCapture(String idempotencyKey) {
    requireKey(idempotencyKey);
    capturesByKey.remove(idempotencyKey);
  }

  /** Refund mirror of {@link #forgetCapture(String)} (backlog #124). */
  public void forgetRefund(String idempotencyKey) {
    requireKey(idempotencyKey);
    refundsByKey.remove(idempotencyKey);
  }

  /**
   * Test-only views of the replay maps' current sizes (backlog #124),
   * mirroring {@link #capturesIssued()}: they let tests prove a
   * successful pay/refund leaves no entry behind while a pre-commit
   * replay still finds its entry. Package-private on purpose — a real PSP
   * client would not expose its idempotency store either.
   */
  int capturesRemembered() {
    return capturesByKey.size();
  }

  int refundsRemembered() {
    return refundsByKey.size();
  }

  private static void requireKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "PSP idempotency key must not be blank");
    }
  }
}
