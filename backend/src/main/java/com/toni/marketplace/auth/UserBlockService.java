package com.toni.marketplace.auth;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * User-to-user blocking (backlog #187).
 *
 * <p>Writes: blocking is idempotent (re-blocking an already-blocked
 * user is a no-op 200 — the {@code (blocker_id, blocked_id)} unique
 * pair in Flyway V47 is the storage-level guarantee, and a concurrent
 * double-block that races the check collapses onto it). Blocking
 * yourself is 422; an unknown target is 404 on both writes. Unblocking
 * a user who is not blocked is equally a no-op.
 *
 * <p>Enforcement is deliberately minimal and lives at the three
 * surfaces the spec pins — message send ({@code MessageService}),
 * browse/search projections ({@code ItemService}) and offer creation
 * ({@code OfferService}) — each consulting
 * {@link #isBlocked(Long, Long)} / {@link #blockedIds(Long)} here
 * through the repository. Blocking never rewrites history: existing
 * threads stay readable, and orders (including a live PAID order)
 * are completely untouched.
 */
@Service
public class UserBlockService {

  private final UserBlockRepository blocks;
  private final UserRepository users;
  private final SellerFollowRepository follows;

  public UserBlockService(UserBlockRepository blocks, UserRepository users,
                          SellerFollowRepository follows) {
    this.blocks = blocks;
    this.users = users;
    this.follows = follows;
  }

  @Transactional
  public void block(Long blockerId, Long blockedId) {
    if (blockerId.equals(blockedId)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
          "cannot block yourself");
    }
    requireUser(blockedId);
    if (blocks.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
      return;
    }
    try {
      blocks.saveAndFlush(new UserBlock(blockerId, blockedId));
    } catch (DataIntegrityViolationException race) {
      // A concurrent block of the same pair won the unique constraint —
      // the end state is exactly the idempotent one, so it is not an error.
    }
    // A follow must never become a channel around a block (backlog
    // #217): blocking a followed seller removes the follow in the same
    // transaction — the follow that the block would otherwise strand is
    // gone atomically with the block itself.
    follows.deleteByFollowerIdAndSellerId(blockerId, blockedId);
  }

  @Transactional
  public void unblock(Long blockerId, Long blockedId) {
    requireUser(blockedId);
    blocks.deleteByBlockerIdAndBlockedId(blockerId, blockedId);
  }

  /** The caller's blocked users, newest block first. */
  @Transactional(readOnly = true)
  public List<BlockedUserDto> listBlocks(Long blockerId) {
    return blocks.findBlockedList(blockerId);
  }

  /**
   * True when {@code blockerId} has blocked {@code blockedId} — the
   * exact-pair check every enforcement surface consults.
   */
  @Transactional(readOnly = true)
  public boolean isBlocked(Long blockerId, Long blockedId) {
    return blockerId != null && blockedId != null
        && blocks.existsByBlockerIdAndBlockedId(blockerId, blockedId);
  }

  /** Every user id {@code blockerId} has blocked (empty for a null caller). */
  @Transactional(readOnly = true)
  public List<Long> blockedIds(Long blockerId) {
    return blockerId == null ? List.of() : blocks.findBlockedIdsByBlockerId(blockerId);
  }

  private void requireUser(Long userId) {
    if (!users.existsById(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
    }
  }
}
