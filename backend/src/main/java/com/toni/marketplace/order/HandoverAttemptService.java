package com.toni.marketplace.order;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable failed-attempt bookkeeping for the handover code (backlog
 * #179).
 *
 * <p>Why a separate bean with {@code REQUIRES_NEW}: a failed complete
 * attempt is answered 400, and the {@code @Transactional}
 * {@code OrderService.complete} rolls its transaction back on that
 * exception — an attempt counter incremented inside the caller's
 * transaction would be erased by its own rejection, and the lockout
 * would never engage. Recording the failure in its own committed
 * transaction (the {@code IdempotencyKeyService} reserve pattern) makes
 * the count survive the 400. The caller must invoke this BEFORE any
 * mutation of its own, so the suspended outer transaction holds no row
 * lock the recorder could deadlock against.
 *
 * <p>Backlog #337 — the increment is a read-modify-write on a
 * {@code @Version} row with no lock, so a burst of concurrent wrong
 * guesses raced: measured on H2, 8 simultaneous failures recorded
 * only 1–3 attempts and the rest died with
 * {@code ObjectOptimisticLockingFailureException} (which the HTTP
 * layer would have answered 409 instead of the identical 400), and
 * the 5-attempt lock never engaged. The row is therefore read with a
 * pessimistic write lock ({@code SELECT ... FOR UPDATE} via
 * {@link OrderRepository#findByIdWithWriteLock}): writers serialise,
 * every increment lands, and the lock engages at the ceiling. A
 * bounded retry was considered and rejected — the lock is held only
 * for this one tiny transaction, and retrying would still leave a
 * residual failure window under contention.
 */
@Service
public class HandoverAttemptService {

  private final OrderRepository orders;

  public HandoverAttemptService(OrderRepository orders) {
    this.orders = orders;
  }

  /**
   * Counts one failed complete attempt against the order, engaging the
   * lock when the consecutive-failure ceiling is reached. An already
   * expired lock is cleared first, so the first failure after expiry
   * starts a fresh allowance (mirrors the #60/#101 lockout shape).
   * The row is read under a pessimistic write lock (backlog #337):
   * concurrent failures serialise instead of losing increments to
   * the {@code @Version} check, so a burst of wrong guesses always
   * engages the lock at the ceiling.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(Long orderId, int maxAttempts, Duration lockDuration, Instant now) {
    Order order = orders.findByIdWithWriteLock(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    if (order.getHandoverLockedUntil() != null
        && !now.isBefore(order.getHandoverLockedUntil())) {
      order.setHandoverLockedUntil(null);
      order.setHandoverFailedAttempts(0);
    }
    int attempts = order.getHandoverFailedAttempts() + 1;
    order.setHandoverFailedAttempts(attempts);
    if (attempts >= maxAttempts) {
      order.setHandoverLockedUntil(now.plus(lockDuration));
    }
    orders.save(order);
  }
}
