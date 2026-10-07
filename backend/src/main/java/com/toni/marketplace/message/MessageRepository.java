package com.toni.marketplace.message;

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
}
