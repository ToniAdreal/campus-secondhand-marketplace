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
  OFFER_DECLINED,
  /** A buyer disputed an order on the caller's listing (backlog #169). */
  ORDER_DISPUTED,
  /** An ADMIN resolved a dispute by refunding the order (backlog #169). */
  DISPUTE_RESOLVED_REFUND,
  /** An ADMIN resolved a dispute by releasing the order (backlog #169). */
  DISPUTE_RESOLVED_RELEASE,
  /**
   * A successful login from an (IP, device) pair the account has never
   * signed in from before (backlog #178). Carries no order or offer id.
   */
  LOGIN_NEW_DEVICE,
  /**
   * New listings matched one of the recipient's saved searches
   * (backlog #182). One batched row per saved search per matcher run:
   * the row carries the saved-search id and how many new listings
   * matched — never one row per listing, and no order or offer id.
   */
  SAVED_SEARCH_MATCH,
  /**
   * A listing the recipient saved dropped in price (backlog #189).
   * The row carries the listing id and the old/new prices — the
   * recipient's favorite-time snapshot and the listing's new price.
   */
  FAVORITE_PRICE_DROP,
  /**
   * An ADMIN upheld the recipient's appeal of an ACTIONED report
   * (backlog #190). The row carries the appeal id; the copy is
   * deliberately generic — whether the listing was actually restored
   * lives on the appeal row ({@code listingRestored}), so the
   * notification never claims a restoration that did not happen.
   */
  REPORT_APPEAL_UPHELD,
  /**
   * An ADMIN rejected the recipient's appeal of an ACTIONED report
   * (backlog #190); the withdrawal stands. The row carries the
   * appeal id.
   */
  REPORT_APPEAL_REJECTED
}
