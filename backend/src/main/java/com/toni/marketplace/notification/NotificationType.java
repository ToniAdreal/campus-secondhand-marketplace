package com.toni.marketplace.notification;

/**
 * The events a user is notified about (backlog #157). Each value names
 * the transition, not the recipient: the recipient is derived from the
 * transition in {@code OrderService} (pay/cancel go to the seller,
 * ship/complete/refund/expire go to the buyer).
 *
 * <p>Backlog #216 adds the per-type preference catalogue data to each
 * constant: a human {@code label} (the settings UI renders exactly
 * this — labels live in this one place and travel over the catalogue
 * endpoint, never duplicated in the SPA) and a {@code security} flag.
 * A security type is an account-security alert: it can never be muted
 * — the preference API rejects muting it with 422, the settings UI
 * renders it locked-on, and the recording seam delivers it even if a
 * muted row somehow exists. Adding a future security type means
 * setting the flag here; nothing else needs to know the list.
 */
public enum NotificationType {
  ORDER_PAID("Order paid", false),
  /**
   * The seller shipped the buyer's PAID order (backlog #238). Mutable
   * per backlog #216 — a buyer who muted this type hears nothing, like
   * the other order types. The row carries the order id; the carrier
   * and tracking number live on the order itself, not on this row.
   */
  ORDER_SHIPPED("Order shipped", false),
  ORDER_COMPLETED("Order completed", false),
  ORDER_REFUNDED("Order refunded", false),
  ORDER_CANCELLED("Order cancelled", false),
  ORDER_EXPIRED("Order expired", false),
  /** The caller's price offer was accepted; an order exists (backlog #168). */
  OFFER_ACCEPTED("Offer accepted", false),
  /** The caller's price offer was declined; no order exists (backlog #168). */
  OFFER_DECLINED("Offer declined", false),
  /** The seller countered the caller's offer at a counter price (backlog #193). */
  OFFER_COUNTERED("Offer countered", false),
  /** The buyer accepted the caller's counter; an order exists at the counter price (backlog #193). */
  OFFER_COUNTER_ACCEPTED("Counter accepted", false),
  /** The buyer declined the caller's counter; no order exists (backlog #193). */
  OFFER_COUNTER_DECLINED("Counter declined", false),
  /**
   * A live offer expired unanswered (backlog #226): a PENDING expiry
   * notifies the buyer (the seller never answered), a COUNTERED expiry
   * notifies the seller (the buyer never answered the counter). The
   * party who let it lapse already knows, so they get nothing. The
   * row carries the offer id, no order id. Mutable like the other
   * offer types (backlog #216) — a muted row writes nothing and fires
   * no SSE hint.
   */
  OFFER_EXPIRED("Offer expired", false),
  /** A buyer disputed an order on the caller's listing (backlog #169). */
  ORDER_DISPUTED("Order disputed", false),
  /** An ADMIN resolved a dispute by refunding the order (backlog #169). */
  DISPUTE_RESOLVED_REFUND("Dispute resolved with a refund", false),
  /** An ADMIN resolved a dispute by releasing the order (backlog #169). */
  DISPUTE_RESOLVED_RELEASE("Dispute resolved and the order released", false),
  /**
   * A successful login from an (IP, device) pair the account has never
   * signed in from before (backlog #178). Carries no order or offer id.
   * A security alert: never mutable (backlog #216).
   */
  LOGIN_NEW_DEVICE("New-device sign-in alert", true),
  /**
   * New listings matched one of the recipient's saved searches
   * (backlog #182). One batched row per saved search per matcher run:
   * the row carries the saved-search id and how many new listings
   * matched — never one row per listing, and no order or offer id.
   */
  SAVED_SEARCH_MATCH("Saved-search matches", false),
  /**
   * A listing the recipient saved dropped in price (backlog #189).
   * The row carries the listing id and the old/new prices — the
   * recipient's favorite-time snapshot and the listing's new price.
   */
  FAVORITE_PRICE_DROP("Price drops on saved listings", false),
  /**
   * An ADMIN upheld the recipient's appeal of an ACTIONED report
   * (backlog #190). The row carries the appeal id; the copy is
   * deliberately generic — whether the listing was actually restored
   * lives on the appeal row ({@code listingRestored}), so the
   * notification never claims a restoration that did not happen.
   */
  REPORT_APPEAL_UPHELD("Report appeal upheld", false),
  /**
   * An ADMIN rejected the recipient's appeal of an ACTIONED report
   * (backlog #190); the withdrawal stands. The row carries the
   * appeal id.
   */
  REPORT_APPEAL_REJECTED("Report appeal rejected", false),
  /**
   * A successful login exceeded the account's concurrent-session cap
   * and the oldest live session was signed out to make room (backlog
   * #191). Carries no order, offer or device data — the sessions page
   * is where the holder reviews what remains signed in. A security
   * alert: never mutable (backlog #216).
   */
  SESSION_EVICTED("Oldest session signed out by a new sign-in", true),
  /**
   * An email change was requested for the recipient's account
   * (backlog #208). Recorded at REQUEST time — while the account's
   * address is still the old one, so the holder reading their
   * notifications at the old address sees the attempt — and never at
   * confirm: by then the switch has already happened. Carries no
   * order, offer or address data. A security alert: never mutable
   * (backlog #216).
   */
  EMAIL_CHANGE_REQUESTED("Email change requested", true),
  /**
   * An ADMIN reset the recipient's TOTP two-factor authentication
   * (backlog #209). The reset signs the account out everywhere and
   * retires its trusted devices, so the row is the holder's visible
   * evidence that staff action — not the holder — turned 2FA off; it
   * must never be silent. Carries no order, offer or credential data.
   * A security alert: never mutable (backlog #216).
   */
  TOTP_RESET_BY_ADMIN("Two-factor authentication reset by an administrator", true),
  /**
   * The recipient enrolled a WebAuthn passkey (backlog #220). A
   * security alert: never mutable (backlog #216) — a passkey the
   * holder did not enroll is account compromise, so enrolment must
   * never be silent.
   */
  PASSKEY_REGISTERED("Passkey registered", true),
  /**
   * The recipient removed one of their passkeys (backlog #220). A
   * security alert: never mutable — a removal the holder did not
   * perform deserves their attention.
   */
  PASSKEY_REMOVED("Passkey removed", true),
  /**
   * A seller the recipient follows published a new listing (backlog
   * #217). The row carries the listing id in the same {@code itemId}
   * column as FAVORITE_PRICE_DROP; the SPA links straight to the
   * listing. A discovery alert, not a security alert: mutable in the
   * per-type preferences (backlog #216).
   */
  SELLER_NEW_LISTING("New listing from a followed seller", false);

  private final String label;
  private final boolean security;

  NotificationType(String label, boolean security) {
    this.label = label;
    this.security = security;
  }

  /** The settings-catalogue label for this type (backlog #216). */
  public String label() {
    return label;
  }

  /**
   * Whether this type is an account-security alert that can never be
   * muted (backlog #216).
   */
  public boolean isSecurity() {
    return security;
  }
}
