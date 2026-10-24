package com.toni.marketplace.order;

/**
 * Lifecycle of one seller payout row (backlog #221). {@code PENDING} is the
 * state a row is born in at order completion; {@code PAYABLE} means the
 * dispute window has passed with no open dispute and the money is due;
 * {@code PAID} means the hourly sweep has marked it paid (the mock pays
 * nobody — there is no real PSP payout here, only the ledger state a real
 * payout run would advance; see {@link SellerPayoutService}).
 *
 * <p>{@code PAYABLE} is a real persisted state, not a same-run transient:
 * the sweep pays only rows that were already {@code PAYABLE} when the run
 * started, so a row promoted in one run is paid no earlier than the next.
 */
public enum PayoutStatus {
  PENDING,
  PAYABLE,
  PAID
}
