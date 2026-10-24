package com.toni.marketplace.order;

import com.toni.marketplace.dispute.DisputeProperties;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-row payout transitions (backlog #221), kept in their own bean
 * so the sweep orchestrator ({@link SellerPayoutService}) calls them
 * through the Spring proxy — one transaction per row, mirroring
 * {@link PendingOrderExpiryService}'s per-row discipline via
 * {@code OrderService}. A row whose order moved, gained an open dispute,
 * or is still inside the window is skipped, never failed: the next sweep
 * retries it, and a concurrent transition winning first surfaces as a
 * version conflict the caller treats as a skip.
 *
 * <p>Window anchor: the order's {@code completedAt} when present (the
 * real completion instant, backlog #180), falling back to
 * {@code updatedAt} for orders completed before that column landed — the
 * same fallback honesty as the dispute window's own
 * {@code updatedAt} anchor, except here a real transition stamp exists
 * for every order completed after #180 and is preferred.
 */
@Service
public class SellerPayoutTransitions {

  private final SellerPayoutRepository payouts;
  private final OrderRepository orders;
  private final OrderDisputeRepository disputes;
  private final DisputeProperties disputeProps;
  private final Clock clock;

  public SellerPayoutTransitions(SellerPayoutRepository payouts,
                                  OrderRepository orders,
                                  OrderDisputeRepository disputes,
                                  DisputeProperties disputeProps,
                                  Clock clock) {
    this.payouts = payouts;
    this.orders = orders;
    this.disputes = disputes;
    this.disputeProps = disputeProps;
    this.clock = clock;
  }

  /**
   * PENDING → PAYABLE when the dispute window has passed with no open
   * dispute. Returns false for every skip reason (wrong status, order
   * no longer COMPLETED, open dispute, window still open) — the sweep
   * counts skips, it does not fail on them.
   */
  @Transactional
  public boolean promoteToPayable(Long payoutId, Instant now) {
    SellerPayout payout = payouts.findById(payoutId).orElse(null);
    if (payout == null || payout.getStatus() != PayoutStatus.PENDING) {
      return false;
    }
    Order order = orders.findById(payout.getOrderId()).orElse(null);
    if (order == null || order.getStatus() != OrderStatus.COMPLETED) {
      return false;
    }
    if (disputes.findByOrderIdAndStatus(order.getId(), DisputeStatus.OPEN).isPresent()) {
      return false;
    }
    Instant anchor = order.getCompletedAt() != null ? order.getCompletedAt() : order.getUpdatedAt();
    if (anchor == null || anchor.plus(disputeProps.getWindow()).isAfter(now)) {
      return false;
    }
    payout.setStatus(PayoutStatus.PAYABLE);
    payout.setPayableAt(now);
    payouts.save(payout);
    return true;
  }

  /**
   * PAYABLE → PAID. Re-checks the order state and the open-dispute gate
   * at pay time: a dispute opened after promotion, or a dispute refund
   * that moved the order to REFUNDED, must never be paid out. Returns
   * false for every skip reason, mirroring {@link #promoteToPayable}.
   */
  @Transactional
  public boolean markPaid(Long payoutId, Instant now) {
    SellerPayout payout = payouts.findById(payoutId).orElse(null);
    if (payout == null || payout.getStatus() != PayoutStatus.PAYABLE) {
      return false;
    }
    Order order = orders.findById(payout.getOrderId()).orElse(null);
    if (order == null || order.getStatus() != OrderStatus.COMPLETED) {
      return false;
    }
    if (disputes.findByOrderIdAndStatus(order.getId(), DisputeStatus.OPEN).isPresent()) {
      return false;
    }
    payout.setStatus(PayoutStatus.PAID);
    payout.setPaidAt(now);
    payouts.save(payout);
    return true;
  }
}
