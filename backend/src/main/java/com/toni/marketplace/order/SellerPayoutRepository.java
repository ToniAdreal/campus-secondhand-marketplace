package com.toni.marketplace.order;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerPayoutRepository extends JpaRepository<SellerPayout, Long> {

  /** The settlement row for one order, if completion created one. */
  Optional<SellerPayout> findByOrderId(Long orderId);

  /** The seller's payout rows, newest first (page sort set by caller). */
  Page<SellerPayout> findBySellerId(Long sellerId, Pageable pageable);

  long countBySellerIdAndStatus(Long sellerId, PayoutStatus status);

  @Query("select coalesce(sum(p.amountCents), 0) from SellerPayout p "
      + "where p.sellerId = :sellerId and p.status = :status")
  long sumAmountBySellerIdAndStatus(@Param("sellerId") Long sellerId,
                                    @Param("status") PayoutStatus status);

  /** All payout ids currently in one status — the sweep's worklist. */
  @Query("select p.id from SellerPayout p where p.status = :status order by p.id asc")
  List<Long> findIdsByStatus(@Param("status") PayoutStatus status);
}
