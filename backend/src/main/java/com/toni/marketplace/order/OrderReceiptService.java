package com.toni.marketplace.order;

import com.toni.marketplace.auth.UserRepository;
import com.toni.marketplace.dispute.DisputeStatus;
import com.toni.marketplace.dispute.OrderDispute;
import com.toni.marketplace.dispute.OrderDisputeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds the printable receipt for one order (backlog #180).
 *
 * <p>Authorisation deliberately differs from
 * {@link OrderService#getOrderFor}: buyer, seller AND ADMIN may read a
 * receipt (all three are parties to — or operators of — the transaction),
 * and anyone else gets 404 with the same message as an unknown id, so a
 * receipt URL never leaks that an order exists. (The plain order read
 * keeps its 403 discipline; the receipt is a shareable document surface,
 * so it takes the stricter no-leak rule the backlog specifies.)
 *
 * <p>Usernames are resolved through {@code UserRepository} — the receipt
 * names the parties, where the order DTOs carry only ids. A missing user
 * row (deleted account) renders as {@code "unknown"} rather than failing
 * the receipt: the document must stay printable for the counterparty.
 */
@Service
public class OrderReceiptService {

  private final OrderRepository orders;
  private final UserRepository users;
  private final PaymentAttemptRecorder attempts;
  private final OrderDisputeRepository disputes;

  public OrderReceiptService(OrderRepository orders, UserRepository users,
                             PaymentAttemptRecorder attempts,
                             OrderDisputeRepository disputes) {
    this.orders = orders;
    this.users = users;
    this.attempts = attempts;
    this.disputes = disputes;
  }

  @Transactional(readOnly = true)
  public ReceiptDto getReceiptFor(Long callerId, boolean callerIsAdmin, Long orderId) {
    Order order = orders.findById(orderId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "order not found"));
    boolean isBuyer = order.getBuyerId().equals(callerId);
    boolean isSeller = order.getItem().getSellerId().equals(callerId);
    if (!callerIsAdmin && !isBuyer && !isSeller) {
      // No existence leak: identical status and message to unknown id.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found");
    }
    boolean paid = order.getCaptureId() != null
        || order.getStatus() == OrderStatus.PAID
        || order.getStatus() == OrderStatus.COMPLETED
        || order.getStatus() == OrderStatus.REFUNDED
        || order.getStatus() == OrderStatus.DISPUTED;
    // Backlog #257: the order's cumulative refunded total is 0 until the
    // first refund — a receipt must not print "refunded ¥0.00" on an
    // order that was never refunded, so 0 maps to null here.
    Long refundedAmountCents = order.getRefundedAmountCents() > 0
        ? order.getRefundedAmountCents() : null;
    // Only a RESOLVED_REFUND dispute's note is a refund note: a release
    // resolution's note explains why money stayed with the seller.
    String disputeResolutionNote = disputes
        .findByOrderIdAndStatus(orderId, DisputeStatus.RESOLVED_REFUND)
        .map(OrderDispute::getResolutionNote)
        .orElse(null);
    return new ReceiptDto(
        order.getId(),
        order.getStatus(),
        paid,
        order.getItem().getId(),
        order.getItem().getTitle(),
        order.getAmountCents(),
        ReceiptDto.formatPrice(order.getAmountCents()),
        usernameOf(order.getBuyerId()),
        usernameOf(order.getItem().getSellerId()),
        order.getCreatedAt(),
        order.getPaidAt(),
        order.getCompletedAt(),
        order.getCaptureId(),
        order.getRefundId(),
        attempts.findLatestRefunded(orderId).map(PaymentAttempt::getRefundReason)
            .orElse(null),
        refundedAmountCents,
        disputeResolutionNote);
  }

  private String usernameOf(Long userId) {
    return users.findById(userId).map(u -> u.getUsername()).orElse("unknown");
  }
}
