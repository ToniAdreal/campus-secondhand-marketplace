package com.toni.marketplace.dispute;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderDisputeRepository extends JpaRepository<OrderDispute, Long> {

  /**
   * The still-open dispute of one order, if any — the lookup behind
   * "opening a second live dispute is idempotent" (backlog #169): a
   * repeat open returns this row instead of inserting a duplicate.
   * The {@code uq_dispute_open_order} guard column is the database-level
   * arbiter under races; this read is the friendly path.
   */
  Optional<OrderDispute> findByOrderIdAndStatus(Long orderId, DisputeStatus status);

  /**
   * Every dispute ever opened for one order, in any state — the lookup
   * behind "re-dispute of a resolved order is 422": any resolved row
   * here means the order's one dispute has already been decided.
   */
  List<OrderDispute> findByOrderId(Long orderId);

  /** The ADMIN queue narrowed to one status; the service supplies newest-first sort. */
  Page<OrderDispute> findByStatus(DisputeStatus status, Pageable pageable);

  /** Disputes currently in one status — the ADMIN dashboard's open-disputes snapshot (#196). */
  long countByStatus(DisputeStatus status);
}
