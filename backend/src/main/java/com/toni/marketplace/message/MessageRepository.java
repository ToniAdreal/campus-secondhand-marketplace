package com.toni.marketplace.message;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Conversation reads only: one page of the per-listing thread for one
 * participant — backed by {@code idx_msg_item_thread (item_id, created_at)}.
 *
 * <p>The sort is fixed by the caller ({@code createdAt desc, id desc}):
 * page 0 is the newest page, and the service reverses each page's content to
 * chronological order so chat UIs can render a page top-to-bottom and stitch
 * older pages above it.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

  @Query("SELECT m FROM Message m WHERE m.itemId = :itemId "
      + "AND (m.senderId = :userId OR m.receiverId = :userId)")
  Page<Message> findThreadPageByItemAndParticipant(
      @Param("itemId") Long itemId, @Param("userId") Long userId, Pageable pageable);

  /**
   * Every still-unread row on one listing addressed to one receiver —
   * the set a thread view stamps read (backlog #106). Loaded as managed
   * entities (not a bulk update) so the page being returned in the same
   * transaction already carries the fresh {@code readAt} in its DTOs.
   */
  @Query("SELECT m FROM Message m WHERE m.itemId = :itemId "
      + "AND m.receiverId = :receiverId AND m.readAt IS NULL")
  List<Message> findUnreadByItemAndReceiver(
      @Param("itemId") Long itemId, @Param("receiverId") Long receiverId);

  /** The caller's total unread across all listings (backlog #106). */
  long countByReceiverIdAndReadAtIsNull(Long receiverId);

  /**
   * Whether any message already exists on this listing between the two
   * users, in either direction — the thread-existence check behind the
   * seller-reply send guard (backlog #112): a seller may only write to a
   * buyer who has already opened a thread on that listing.
   */
  @Query("SELECT COUNT(m) > 0 FROM Message m WHERE m.itemId = :itemId "
      + "AND ((m.senderId = :userA AND m.receiverId = :userB) "
      + "OR (m.senderId = :userB AND m.receiverId = :userA))")
  boolean existsThreadBetween(
      @Param("itemId") Long itemId,
      @Param("userA") Long userA,
      @Param("userB") Long userB);
}
