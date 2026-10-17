package com.toni.marketplace.order;

import com.toni.marketplace.auth.UserRepository;
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

  public OrderReceiptService(OrderRepository orders, UserRepository users) {
    this.orders = orders;
    this.users = users;
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
        order.getRefundId());
  }

  private String usernameOf(Long userId) {
    return users.findById(userId).map(u -> u.getUsername()).orElse("unknown");
  }
}
