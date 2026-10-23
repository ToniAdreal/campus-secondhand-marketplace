package com.toni.marketplace.auth;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Following a seller's listings (backlog #217).
 *
 * <p>Writes mirror {@link UserBlockService}: following is idempotent
 * (re-following an already-followed seller is a no-op 200 — the
 * {@code (follower_id, seller_id)} unique pair in Flyway V63 is the
 * storage-level guarantee, and a concurrent double-follow that races
 * the check collapses onto it). Following yourself is 422; an unknown
 * seller id is 404 on both writes; unfollowing a seller who is not
 * followed is equally a no-op.
 *
 * <p>Block interplay (backlog #187): following a seller the caller has
 * blocked is 409 — a follow must never become a channel around a
 * block. The reverse direction is handled in
 * {@link UserBlockService#block}: blocking a followed seller removes
 * the follow in the same transaction, so the two invariants can never
 * coexist.
 */
@Service
public class SellerFollowService {

  private final SellerFollowRepository follows;
  private final UserBlockRepository blocks;
  private final UserRepository users;

  public SellerFollowService(SellerFollowRepository follows,
                             UserBlockRepository blocks,
                             UserRepository users) {
    this.follows = follows;
    this.blocks = blocks;
    this.users = users;
  }

  @Transactional
  public void follow(Long followerId, Long sellerId) {
    if (followerId.equals(sellerId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "cannot follow yourself");
    }
    requireUser(sellerId);
    if (blocks.existsByBlockerIdAndBlockedId(followerId, sellerId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "you have blocked this seller");
    }
    if (follows.existsByFollowerIdAndSellerId(followerId, sellerId)) {
      return;
    }
    try {
      follows.saveAndFlush(new SellerFollow(followerId, sellerId));
    } catch (DataIntegrityViolationException race) {
      // A concurrent follow of the same pair won the unique constraint —
      // the end state is exactly the idempotent one, so it is not an error.
    }
  }

  @Transactional
  public void unfollow(Long followerId, Long sellerId) {
    requireUser(sellerId);
    follows.deleteByFollowerIdAndSellerId(followerId, sellerId);
  }

  /** The caller's following list, newest follow first. */
  @Transactional(readOnly = true)
  public List<FollowedSellerDto> listFollows(Long followerId) {
    return follows.findFollowingList(followerId);
  }

  private void requireUser(Long userId) {
    if (!users.existsById(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
    }
  }
}
