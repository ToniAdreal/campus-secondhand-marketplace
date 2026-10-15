package com.toni.marketplace.offer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceOfferRepository extends JpaRepository<PriceOffer, Long> {

  /** The buyer's own offers; the service supplies newest-first sort. */
  Page<PriceOffer> findByBuyerId(Long buyerId, Pageable pageable);

  /** Offers on the seller's own listings; newest-first via the service. */
  Page<PriceOffer> findBySellerId(Long sellerId, Pageable pageable);

  /**
   * One offer scoped to its buyer: a withdraw (or any buyer-side read)
   * for someone else's offer finds nothing, so the service answers a
   * plain 404 without leaking that the row exists — the same
   * no-existence-leak rule as notifications / saved searches.
   */
  Optional<PriceOffer> findByIdAndBuyerId(Long id, Long buyerId);

  /** Seller-side counterpart of {@link #findByIdAndBuyerId}. */
  Optional<PriceOffer> findByIdAndSellerId(Long id, Long sellerId);

  /**
   * Application-level counterpart of the {@code uq_offer_live_pair}
   * constraint: true when this buyer already holds a live offer in the
   * given status on this item. Checked before creating for a friendly
   * 409; the database constraint remains the final arbiter under races.
   */
  boolean existsByItem_IdAndBuyerIdAndStatus(Long itemId, Long buyerId, OfferStatus status);

  /**
   * Worklist for the stale-offer expiry job: ids of offers in the given
   * status whose deadline has passed. Projects only the id so the batch
   * scan stays narrow; the per-row expiry re-checks the status under its
   * own transaction, so rows answered between the scan and the row
   * transaction are skipped, never wrongly expired.
   */
  @Query("select o.id from PriceOffer o where o.status = :status and o.expiresAt <= :now")
  List<Long> findIdsByStatusAndExpiresAtBefore(@Param("status") OfferStatus status,
                                               @Param("now") Instant now,
                                               Pageable pageable);
}
