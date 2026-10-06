package com.toni.marketplace.message;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Conversation reads only: the full per-listing thread for one participant,
 * oldest first — backed by {@code idx_msg_item_thread (item_id, created_at)}.
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

  @Query("SELECT m FROM Message m WHERE m.itemId = :itemId "
      + "AND (m.senderId = :userId OR m.receiverId = :userId) "
      + "ORDER BY m.createdAt ASC, m.id ASC")
  List<Message> findThreadByItemAndParticipant(
      @Param("itemId") Long itemId, @Param("userId") Long userId);
}
