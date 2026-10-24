package com.toni.marketplace.audit;

/**
 * The sensitive transitions the audit log records (backlog #98). One value
 * per audited action; the set is deliberately small — v1 covers the seams
 * that exist today (password change, TOTP enable, order complete/refund,
 * ADMIN disable/enable); backlog #122 adds the order pay / buyer-cancel
 * transitions and backlog #132 the ADMIN category writes. New audited
 * transitions add a value here rather than free-text strings, so the
 * trail stays queryable.
 */
public enum AuditAction {
  /** The account holder changed their password ({@code AuthService.changePassword}). */
  PASSWORD_CHANGED,
  /**
   * The account holder changed their account email address
   * ({@code AuthService.changeEmail}, backlog #150). Actor and target are
   * both the account holder, mirroring {@link #PASSWORD_CHANGED}. The
   * audit table deliberately carries no payload column (see AuditLog), so
   * the old/new addresses are NOT stored here — the row is evidence that
   * the change happened, and the addresses themselves remain only on the
   * user row's history-free current value.
   */
  EMAIL_CHANGED,
  /**
   * The account holder closed their own account
   * ({@code AuthService.closeAccount}, backlog #158). Actor and target are
   * both the account holder, mirroring {@link #PASSWORD_CHANGED}. The row
   * survives the closure precisely because the audit table carries no
   * foreign keys — it is the evidence that the tombstoned user row was a
   * self-service closure, not an ADMIN disable ({@link #USER_DISABLED}).
   */
  ACCOUNT_CLOSED,
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
   * The account holder regenerated their TOTP recovery-code set
   * ({@code AuthService.regenerateRecoveryCodes}, backlog #127). Actor
   * and target are both the account holder, mirroring
   * {@link #TOTP_ENABLED}: regeneration required the current password
   * plus a valid TOTP or recovery code, deleted the whole previous set
   * and issued a fresh one in the same transaction — the TOTP secret
   * and the enabled flag are untouched (no re-enrollment).
   */
  RECOVERY_CODES_REGENERATED,
  /**
   * An ADMIN reset a locked-out account's TOTP two-factor
   * ({@code AuthService.resetTotpByAdmin}, backlog #209). Actor is the
   * acting ADMIN; target is the reset account — deliberately distinct
   * from {@link #TOTP_DISABLED}, whose actor is the account holder
   * proving both factors themselves: the trail keeps "holder turned
   * 2FA off" and "staff reset 2FA for a locked-out holder" apart. A
   * refused reset (ADMIN target, 2FA not enabled) writes no row.
   */
  TOTP_RESET_BY_ADMIN,
  /**
   * The account holder enrolled a WebAuthn passkey
   * ({@code AuthService.finishPasskeyRegistration}, backlog #220).
   * Actor and target are both the account holder, mirroring {@link
   * #TOTP_ENABLED}: enrolment required a fresh step-up proof, and only
   * the public half of the credential is stored.
   */
  PASSKEY_REGISTERED,
  /**
   * The account holder removed one of their passkeys
   * ({@code AuthService.deletePasskey}, backlog #220). Actor and
   * target are both the account holder, mirroring {@link
   * #TOTP_DISABLED}: removal required a fresh step-up proof.
   * Removing the last passkey drops the account to its remaining
   * factors — an explicit, audited choice.
   */
  PASSKEY_REMOVED,
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
  /**
   * A seller (or ADMIN) shipped a PAID order ({@code OrderService.ship},
   * backlog #222): PAID → SHIPPED with the carrier and tracking number
   * recorded. Actor is the shipping seller (or ADMIN).
   */
  ORDER_SHIPPED,
  /**
   * The buyer confirmed receipt of a SHIPPED order
   * ({@code OrderService.confirmReceipt}, backlog #222): SHIPPED →
   * COMPLETED with the same RESERVED → SOLD listing flip as a handover
   * completion. Actor is the confirming buyer — distinct from
   * {@link #ORDER_COMPLETED} so the trail shows which completion path
   * an order took.
   */
  ORDER_RECEIPT_CONFIRMED,
  /**
   * An ADMIN completed a PAID order WITHOUT the buyer's handover code
   * ({@code OrderService.complete}, backlog #179). Actor is the
   * completing ADMIN. Deliberately distinct from
   * {@link #ORDER_COMPLETED}: since #179 a seller completion proves the
   * buyer handed over the code, while an ADMIN completion is an
   * explicit override of that proof — the trail keeps the two apart so
   * an override is visible as one, never dressed as a code-verified
   * handover.
   */
  ORDER_COMPLETED_ADMIN_OVERRIDE,
  /** A seller (or ADMIN) refunded a PAID order ({@code OrderService.refund}). */
  ORDER_REFUNDED,
  /**
   * The buyer opened a dispute on a PAID/COMPLETED order
   * ({@code DisputeService.openDispute}, backlog #169). Actor is the
   * disputing buyer. An idempotent re-open writes no second row.
   */
  ORDER_DISPUTED,
  /**
   * An ADMIN resolved a dispute by refunding the order
   * ({@code DisputeService.resolveRefund}, backlog #169). Actor is the
   * resolving ADMIN — deliberately distinct from {@link #ORDER_REFUNDED},
   * whose actor is the seller/ADMIN caller of the voluntary refund: the
   * trail keeps "seller chose to refund" and "dispute forced a refund"
   * apart. An idempotent re-resolve writes no second row.
   */
  ORDER_DISPUTE_RESOLVED_REFUND,
  /**
   * An ADMIN resolved a dispute by releasing the order back to its
   * pre-dispute status ({@code DisputeService.resolveRelease}, backlog
   * #169). Actor is the resolving ADMIN. An idempotent re-resolve writes
   * no second row.
   */
  ORDER_DISPUTE_RESOLVED_RELEASE,
  /**
   * The listing's seller appealed an ACTIONED report
   * ({@code ReportAppealService.createAppeal}, backlog #190). Actor is
   * the appealing seller. An idempotent re-file (returning the open
   * appeal) writes no second row.
   */
  REPORT_APPEAL_OPENED,
  /**
   * An ADMIN upheld a report appeal
   * ({@code ReportAppealService.uphold}, backlog #190). Actor is the
   * resolving ADMIN. An idempotent re-uphold writes no second row.
   */
  REPORT_APPEAL_UPHELD,
  /**
   * An ADMIN rejected a report appeal
   * ({@code ReportAppealService.reject}, backlog #190). Actor is the
   * resolving ADMIN. An idempotent re-reject writes no second row.
   */
  REPORT_APPEAL_REJECTED,
  /**
   * The account holder downloaded their self-service data export
   * ({@code AccountDataExportService.export}, backlog #172). Actor and
   * target are both the account holder, mirroring
   * {@link #PASSWORD_CHANGED}: the export is the broadest read of one
   * account's data in the app, so the read itself is the auditable
   * event — the row is evidence it happened, and it stores none of the
   * exported data (the audit table carries no payload column).
   */
  /**
   * The account holder passed the step-up confirmation for a data
   * export and a single-use export grant was minted
   * ({@code DataExportGrantService.confirm}, backlog #188). Actor and
   * target are both the account holder. A failed proof writes no row
   * (the proof transaction produces no grant); the row is evidence
   * the fresh proof happened, and it stores neither the grant nor any
   * exported data (the audit table carries no payload column).
   */
  DATA_EXPORT_GRANT_ISSUED,
  DATA_EXPORTED,
  /** An ADMIN disabled an account ({@code AuthService.disableUser}). */
  USER_DISABLED,
  /** An ADMIN re-enabled an account ({@code AuthService.enableUser}). */
  USER_ENABLED,
  /**
   * An ADMIN created a listing category ({@code CategoryService.createCategory},
   * backlog #132). Actor is the acting ADMIN; target is the new category.
   * A rejected create (duplicate name/slug, invalid slug) writes no row —
   * the exception rolls the transaction back before the record call.
   */
  CATEGORY_CREATED,
  /**
   * An ADMIN renamed a listing category ({@code CategoryService.updateCategory},
   * backlog #132). Actor is the acting ADMIN; target is the renamed
   * category. The rename endpoint itself lands with #132 — before it,
   * categories could only be created and deleted, so there was no update
   * transition to audit.
   */
  CATEGORY_UPDATED,
  /**
   * An ADMIN deleted a listing category ({@code CategoryService.deleteCategory},
   * backlog #132). Actor is the acting ADMIN; target is the deleted
   * category's id — the row survives the deletion precisely because the
   * audit table carries no foreign keys. A delete rejected as in-use
   * (409) writes no row.
   */
  CATEGORY_DELETED,
  /**
   * An ADMIN replayed a recorded webhook event
   * ({@code PaymentWebhookService.replayWebhookEvent}, backlog #170).
   * Actor is the replaying ADMIN; target is the webhook_event row.
   * Unlike the transition actions above, EVERY replay attempt writes a
   * row — including a deduped no-op and an attempt the freshness
   * re-check refuses with 401 — because the attempt itself is the
   * auditable event here (mirrored on the ledger row by its
   * replay_count / last_replay_at columns).
   */
  WEBHOOK_EVENT_REPLAYED
}
