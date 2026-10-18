package com.toni.marketplace.auth;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

  /** Exact-pair existence — the enforcement check on every guarded path. */
  boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

  /** Scoped by blocker on purpose: one user can never delete another's block. */
  void deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

  /** Every user id the given blocker has blocked — the browse exclusion set. */
  @Query("select b.blockedId from UserBlock b where b.blockerId = :blockerId")
  List<Long> findBlockedIdsByBlockerId(@Param("blockerId") Long blockerId);

  /**
   * The blocker's blocked-users list as a DTO projection (one SELECT
   * joining the blocked user's public username — no per-row lazy
   * loads), newest block first with the id tiebreak keeping the order
   * deterministic for blocks landing in the same instant.
   */
  @Query(
      "select new com.toni.marketplace.auth.BlockedUserDto("
          + "b.blockedId, u.username, b.createdAt) "
          + "from UserBlock b join User u on u.id = b.blockedId "
          + "where b.blockerId = :blockerId "
          + "order by b.createdAt desc, b.id desc")
  List<BlockedUserDto> findBlockedList(@Param("blockerId") Long blockerId);
}
