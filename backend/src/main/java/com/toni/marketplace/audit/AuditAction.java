package com.toni.marketplace.audit;

/**
 * The sensitive transitions the audit log records (backlog #98). One value
 * per audited action; the set is deliberately small — v1 covers the seams
 * that exist today (password change, TOTP enable, order complete/refund,
 * ADMIN disable/enable). New audited transitions add a value here rather
 * than free-text strings, so the trail stays queryable.
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
  /** A seller (or ADMIN) completed a PAID order ({@code OrderService.complete}). */
  ORDER_COMPLETED,
  /** A seller (or ADMIN) refunded a PAID order ({@code OrderService.refund}). */
  ORDER_REFUNDED,
  /** An ADMIN disabled an account ({@code AuthService.disableUser}). */
  USER_DISABLED,
  /** An ADMIN re-enabled an account ({@code AuthService.enableUser}). */
  USER_ENABLED
}
