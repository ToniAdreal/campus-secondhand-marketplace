package com.toni.marketplace.order;

/** Totals over one seller's payout ledger (backlog #221). */
public record SellerPayoutTotalsDto(
    long pendingCount,
    long pendingCents,
    long payableCount,
    long payableCents,
    long paidCount,
    long paidCents,
    long adjustmentsCents,
    long netCents) {

  static SellerPayoutTotalsDto of(long pendingCount, long pendingCents,
                                  long payableCount, long payableCents,
                                  long paidCount, long paidCents,
                                  long adjustmentsCents) {
    return new SellerPayoutTotalsDto(
        pendingCount, pendingCents, payableCount, payableCents,
        paidCount, paidCents, adjustmentsCents,
        pendingCents + payableCents + paidCents + adjustmentsCents);
  }
}
