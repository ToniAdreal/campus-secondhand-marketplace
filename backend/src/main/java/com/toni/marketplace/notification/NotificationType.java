package com.toni.marketplace.notification;

/**
 * The order-lifecycle events a user is notified about (backlog #157).
 * Each value names the transition, not the recipient: the recipient is
 * derived from the transition in {@code OrderService} (pay/cancel go to
 * the seller, complete/refund/expire go to the buyer).
 */
public enum NotificationType {
  ORDER_PAID,
  ORDER_COMPLETED,
  ORDER_REFUNDED,
  ORDER_CANCELLED,
  ORDER_EXPIRED,
  /** The caller's price offer was accepted; an order exists (backlog #168). */
  OFFER_ACCEPTED,
  /** The caller's price offer was declined; no order exists (backlog #168). */
  OFFER_DECLINED
}
