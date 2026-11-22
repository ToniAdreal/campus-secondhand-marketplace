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
   * The account holder changed their username
   * ({@code AuthService.changeUsername}, backlog #256). Actor and target are
   * both the account holder, mirroring {@link #EMAIL_CHANGED}. The audit
   * table deliberately carries no payload column (see AuditLog), so the
   * old/new names are NOT stored here — the row is evidence that the
   * change happened, and the names themselves remain only on the user
   * row's history-free current value. The freed name returns to the pool
   * immediately (see {@code AuthService.changeUsername}); no reservation
   * row exists to consult.
   */
  USERNAME_CHANGED,
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
   * An ADMIN marked a PAYABLE seller payout PAID
   * ({@code SellerPayoutService.markPaidByAdmin}, backlog #390). Actor is
   * the acting ADMIN; target is the payout row. The hourly sweep's own
   * PAYABLE → PAID flip ({@code SellerPayoutTransitions.markPaid}) writes
   * no row: it is a system transition with no actor, and stamping the
   * seller as its actor would falsify the trail (the #122 stale-order
   * rule) — the audited mark-paid is the human ADMIN action, and the
   * sweep's flip is visible instead through the PAYOUT_PAID notification
   * and the payouts.paid counter. An idempotent re-mark of an already
   * PAID row writes no second row.
   */
  PAYOUT_MARKED_PAID,
  /**
   * A party opened a dispute on a PAID/COMPLETED order
   * ({@code DisputeService.openDispute}, backlog #169; seller-opened
   * since backlog #255). Actor is the disputing party. An idempotent
   * re-open writes no second row.
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
   * A dispute party (or an ADMIN) attached an evidence photo to an OPEN
   * dispute ({@code DisputeEvidenceService.addEvidence}, backlog #413).
   * Actor is the uploader; target is the evidence row
   * ({@link AuditTargetType#DISPUTE_EVIDENCE}) — the audit table carries
   * no payload column, so the row stores the evidence id and nothing of
   * the file (no bytes, no {@code /uploads/} URL). The dispute id and
   * order id are one lookup away: the evidence row carries
   * {@code dispute_id} and the dispute carries {@code order_id}, and one
   * dispute per order (#169) makes the chain unambiguous.
   */
  DISPUTE_EVIDENCE_UPLOADED,
  /**
   * A dispute party (the uploader) or an ADMIN removed an evidence photo
   * ({@code DisputeEvidenceService.removeEvidence}, backlog #413). Actor
   * is the remover; target is the deleted evidence row's id — the row
   * survives the deletion precisely because the audit table carries no
   * foreign keys (the {@code CATEGORY_DELETED} rule), so a deleted photo
   * still leaves a trail where before #413 it left none. Same
   * no-bytes/no-URL discipline as {@link #DISPUTE_EVIDENCE_UPLOADED}.
   */
  DISPUTE_EVIDENCE_DELETED,
  /**
   * A seller accepted a PENDING price offer ({@code OfferService.accept},
   * backlog #389). Actor is the accepting seller; target is the offer.
   * The order is created at the AGREED price through
   * {@code OrderCreationService} in the same transaction, so the row and
   * the order commit or roll back together. A sequential re-accept is
   * 422 before reaching the record call and writes no second row.
   */
  OFFER_ACCEPTED,
  /**
   * A price offer (or its counter) was declined ({@code
   * OfferService.decline} for a seller declining a PENDING offer,
   * {@code OfferService.counterDecline} for a buyer declining the
   * seller's counter, backlog #389). Actor is the declining side — the
   * seller on a PENDING decline, the buyer on a counter-decline — which
   * is what tells the two apart, since both end the negotiation
   * DECLINED. No order is created and the listing is untouched.
   */
  OFFER_DECLINED,
  /**
   * A seller countered a PENDING price offer ({@code OfferService.counter},
   * backlog #389). Actor is the countering seller; target is the offer.
   * The counter price is carried on the row as negotiation history.
   */
  OFFER_COUNTERED,
  /**
   * A buyer accepted the seller's counter price ({@code
   * OfferService.counterAccept}, backlog #389). Actor is the accepting
   * buyer; target is the offer. The order is created at the COUNTER
   * price through {@code OrderCreationService} in the same transaction,
   * so the row and the order commit or roll back together. A sequential
   * re-accept is 422 before reaching the record call and writes no
   * second row.
   */
  OFFER_COUNTER_ACCEPTED,
  /**
   * The expiry job flipped a stale PENDING or COUNTERED offer to
   * EXPIRED ({@code OfferService.expireStaleOffer}, backlog #389).
   * Actor is the party whose answer was awaited and never came — the
   * seller on a PENDING expiry, the buyer on a COUNTERED expiry — so
   * the row attributes the lapse to the side that let the deadline
   * pass; the waiting party is the notification recipient, not the
   * actor. Deliberately distinct from the #122 stale-order rule (an
   * order expiry writes no row at all): an offer expiry is the end of
   * a negotiation the trail is meant to show, and the action name
   * itself says the system expired the row — the actor identifies whose
   * unanswered deadline it was, never someone who performed an expiry.
   * The lazy flip in the decision endpoints writes no row: it rolls
   * back with the rejected decision, and the job's flip is the audited
   * one. A sweep re-run finds the row EXPIRED and writes no second row.
   */
  OFFER_EXPIRED,
  /**
   * The buyer left a review on a COMPLETED order
   * ({@code ReviewService.createReview}, backlog #390). Actor is the
   * reviewing buyer; target is the review row. The one-review-per-order
   * guard (409 on a second create) means a second row for the same order
   * can never be written through this action.
   */
  REVIEW_CREATED,
  /**
   * The buyer edited their own review inside the edit window
   * ({@code ReviewService.editReview}, backlog #390). Actor is the
   * editing buyer; target is the review row. Every successful edit
   * writes a row — the edit replaces the rating and body in place and
   * there is no edit history on the row itself, so the trail is the
   * only record that an earlier version existed. A 422 (window closed
   * or seller already responded) writes none.
   */
  REVIEW_EDITED,
  /**
   * The seller set or replaced the public response on one of their
   * reviews ({@code ReviewService.respondToReview}, backlog #390).
   * Actor is the responding seller; target is the review row. A second
   * response with identical text writes no second row: the response is
   * a single-column overwrite and the PUT is idempotent by design, so
   * an identical re-PUT is no transition at all. A response with
   * different text is a new transition and writes a row.
   */
  REVIEW_RESPONDED,
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
   * An ADMIN dismissed a report without acting on its listing
   * ({@code ListingReportService.dismiss}, backlog #397). Actor is the
   * resolving ADMIN; target is the report row. An idempotent
   * re-dismiss writes no second row.
   */
  REPORT_DISMISSED,
  /**
   * An ADMIN actioned a report and hid its listing
   * ({@code ListingReportService.action}, backlog #397). Actor is the
   * resolving ADMIN; target is the listing (item row). The row is
   * evidence of the ADMIN's decision: when the listing was RESERVED or
   * SOLD the withdrawal itself is skipped by the #160 rule (a live
   * order is protected, a sold listing has nothing left to withdraw),
   * so the row does not by itself prove the listing's bytes flipped —
   * the item row is the source of truth for listing state. An
   * idempotent re-action writes no second row.
   */
  LISTING_HIDDEN,
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
