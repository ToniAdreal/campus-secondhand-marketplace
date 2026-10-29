package com.toni.marketplace.order;

import com.toni.marketplace.auth.User;
import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.dispute.DisputeProperties;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
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
 *
 * <p>Seller-account hold (backlog #266): promotion and payment both
 * skip a row while its seller's account is disabled (backlog #88) or
 * closed. Closure needs no separate flag: {@code AuthService.closeAccount}
 * tombstones the row with {@code disabled = true} (plus an anonymized
 * {@code deleted-<id>} identity), so the one flag is the hold for both
 * states — a closed account can never slip through on a forgotten
 * second check. A missing seller row holds too (fail closed: the ledger
 * must not advance money, even mock money, toward an account that no
 * longer exists). The hold is not terminal: re-enabling the account
 * lets the next sweep promote/pay the row with no manual release step.
 */
@Service
public class SellerPayoutTransitions {

  private final SellerPayoutRepository payouts;
  private final OrderRepository orders;
  private final OrderDisputeRepository disputes;
  private final UserRepository users;
  private final DisputeProperties disputeProps;
  private final Clock clock;

  public SellerPayoutTransitions(SellerPayoutRepository payouts,
                                  OrderRepository orders,
                                  OrderDisputeRepository disputes,
                                  UserRepository users,
                                  DisputeProperties disputeProps,
                                  Clock clock) {
    this.payouts = payouts;
    this.orders = orders;
    this.disputes = disputes;
    this.users = users;
    this.disputeProps = disputeProps;
    this.clock = clock;
  }

  /**
   * Whether the sweep must hold this row for the seller's account state:
   * true when the seller row is missing or its account is disabled
   * (which, via the {@code closeAccount} tombstone, also covers closed
   * accounts). Read-only, so the sweep can count holds separately from
   * the other skip reasons before it calls a transition.
   */
  @Transactional(readOnly = true)
  public boolean isSellerHeld(Long payoutId) {
    Optional<SellerPayout> payout = payouts.findById(payoutId);
    if (payout.isEmpty()) {
      return false;
    }
    Optional<User> seller = users.findById(payout.get().getSellerId());
    return seller.isEmpty() || seller.get().isDisabled();
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
    if (isSellerHeld(payoutId)) {
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
    if (isSellerHeld(payoutId)) {
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
