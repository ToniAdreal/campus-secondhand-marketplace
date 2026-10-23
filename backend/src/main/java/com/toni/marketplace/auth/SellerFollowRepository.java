package com.toni.marketplace.auth;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SellerFollowRepository extends JpaRepository<SellerFollow, Long> {

  /** Exact-pair existence — the follow/unfollow idempotency check. */
  boolean existsByFollowerIdAndSellerId(Long followerId, Long sellerId);

  /** Scoped by follower on purpose: one user can never delete another's follow. */
  void deleteByFollowerIdAndSellerId(Long followerId, Long sellerId);

  /**
   * Every follower id of the given seller — the publish fan-out set
   * (backlog #217). Keyed on the unique index's seller half.
   */
  @Query("select f.followerId from SellerFollow f where f.sellerId = :sellerId")
  List<Long> findFollowerIdsBySellerId(@Param("sellerId") Long sellerId);

  /**
   * The caller's following list as a DTO projection (one SELECT joining
   * the seller's public username — no per-row lazy loads), newest follow
   * first with the id tiebreak keeping the order deterministic for
   * follows landing in the same instant. Mirrors
   * {@link UserBlockRepository#findBlockedList}.
   */
  @Query(
      "select new com.toni.marketplace.auth.FollowedSellerDto("
          + "f.sellerId, u.username, f.createdAt) "
          + "from SellerFollow f join User u on u.id = f.sellerId "
          + "where f.followerId = :followerId "
          + "order by f.createdAt desc, f.id desc")
  List<FollowedSellerDto> findFollowingList(@Param("followerId") Long followerId);
}
