package com.toni.marketplace.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerPayoutAdjustmentRepository
    extends JpaRepository<SellerPayoutAdjustment, Long> {

  /** Every compensating entry on one settlement row. */
  List<SellerPayoutAdjustment> findByPayoutId(Long payoutId);

  /** One batch query for a whole page of settlement rows (no N+1). */
  List<SellerPayoutAdjustment> findByPayoutIdIn(List<Long> payoutIds);

  @Query("select coalesce(sum(a.amountCents), 0) from SellerPayoutAdjustment a "
      + "where a.payoutId = :payoutId")
  long sumAmountByPayoutId(@Param("payoutId") Long payoutId);

  @Query("select coalesce(sum(a.amountCents), 0) from SellerPayoutAdjustment a "
      + "where a.sellerId = :sellerId")
  long sumAmountBySellerId(@Param("sellerId") Long sellerId);
}
