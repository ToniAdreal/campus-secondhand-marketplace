package com.toni.marketplace.order;

/**
 * One seller payout row for the seller's list (backlog #221), with the
 * ledger view attached: {@code amountCents} is the settlement figure from
 * completion, {@code adjustmentsCents} sums the compensating entries
 * (negative or zero), and {@code netCents} is what the seller is actually
 * owed on this order once refunds after completion are accounted for.
 * The totals endpoint reconciles against the #108 sales summary by
 * construction: gross there counts PAID/COMPLETED order snapshots, while
 * these totals count only completed orders' net settlements — the two
 * agree exactly when no post-completion refund exists, and diverge by
 * exactly the adjustments when one does.
 */
public record SellerPayoutDto(
    long id,
    long orderId,
    long sellerId,
    long amountCents,
    long adjustmentsCents,
    long netCents,
    PayoutStatus status,
    String createdAt,
    String payableAt,
    String paidAt) {

  static SellerPayoutDto from(SellerPayout payout, long adjustmentsCents) {
    return new SellerPayoutDto(
        payout.getId(),
        payout.getOrderId(),
        payout.getSellerId(),
        payout.getAmountCents(),
        adjustmentsCents,
        payout.getAmountCents() + adjustmentsCents,
        payout.getStatus(),
        payout.getCreatedAt() == null ? null : payout.getCreatedAt().toString(),
        payout.getPayableAt() == null ? null : payout.getPayableAt().toString(),
        payout.getPaidAt() == null ? null : payout.getPaidAt().toString());
  }
}
