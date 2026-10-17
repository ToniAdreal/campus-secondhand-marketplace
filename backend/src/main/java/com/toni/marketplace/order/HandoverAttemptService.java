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
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(Long orderId, int maxAttempts, Duration lockDuration, Instant now) {
    Order order = orders.findById(orderId)
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
