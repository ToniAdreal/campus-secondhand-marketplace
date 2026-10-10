package com.toni.marketplace.audit;

/**
 * The sensitive transitions the audit log records (backlog #98). One value
 * per audited action; the set is deliberately small — v1 covers the seams
 * that exist today (password change, TOTP enable, order complete/refund,
 * ADMIN disable/enable); backlog #122 adds the order pay / buyer-cancel
 * transitions. New audited transitions add a value here rather than
 * free-text strings, so the trail stays queryable.
 */
public enum AuditAction {
  /** The account holder changed their password ({@code AuthService.changePassword}). */
  PASSWORD_CHANGED,
  /**
   * A password reset was completed via the emailed-token flow
   * ({@code PasswordResetService.confirmReset}, backlog #110). Actor and
   * target are both the account whose password was reset: the caller is a
   * token holder, not an authenticated principal, but the token resolves
   * to exactly one account, and the row is evidence that that account's
   * credential changed through the reset path — kept distinct from
   * {@link #PASSWORD_CHANGED}'s authenticated change.
   */
  PASSWORD_RESET_COMPLETED,
  /** The account holder completed TOTP enrollment ({@code AuthService.enableTotp}). */
  TOTP_ENABLED,
  /**
   * The account holder turned TOTP 2FA off ({@code AuthService.disableTotp},
   * backlog #121). Actor and target are both the account holder, mirroring
   * {@link #TOTP_ENABLED}: disabling required the current password plus a
   * valid TOTP or recovery code, cleared the secret and deleted the whole
   * recovery-code set in the same transaction.
   */
  TOTP_DISABLED,
  /**
   * The buyer paid a PENDING order ({@code OrderService.pay}, backlog
   * #122). Actor is the buyer — the money-moving transition PENDING → PAID.
   * An idempotent re-pay writes no second row, and a declined capture
   * writes none (its transaction rolls back).
   */
  ORDER_PAID,
  /**
   * The buyer cancelled a PENDING order ({@code OrderService.cancel},
   * backlog #122). Actor is the cancelling buyer. An idempotent
   * re-cancel writes no second row. Deliberately NOT used for stale-order
   * expiry ({@code OrderService.expireStaleOrder}): expiry is a system
   * transition with no actor, and stamping the buyer as actor would
   * falsify the trail.
   */
  ORDER_CANCELLED,
  /** A seller (or ADMIN) completed a PAID order ({@code OrderService.complete}). */
  ORDER_COMPLETED,
  /** A seller (or ADMIN) refunded a PAID order ({@code OrderService.refund}). */
  ORDER_REFUNDED,
  /** An ADMIN disabled an account ({@code AuthService.disableUser}). */
  USER_DISABLED,
  /** An ADMIN re-enabled an account ({@code AuthService.enableUser}). */
  USER_ENABLED
}
