package com.toni.marketplace.notification;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  /** The recipient's own notifications; the service supplies newest-first sort. */
  Page<Notification> findByRecipientId(Long recipientId, Pageable pageable);

  /** The recipient's total unread (backlog #157) — the nav bell's count. */
  long countByRecipientIdAndReadAtIsNull(Long recipientId);

  /**
   * One notification scoped to its recipient: a mark-read for someone
   * else's row finds nothing, so the service can answer a plain 404
   * without leaking that the row exists.
   */
  Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

  /**
   * Stamps every unread row of one recipient in a single update
   * (backlog #173); returns the number of rows stamped, so a repeat
   * call answers 0. Scoped by recipient id — no other user's row can
   * be touched.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update Notification n set n.readAt = :when"
      + " where n.recipientId = :recipientId and n.readAt is null")
  int markAllRead(@Param("recipientId") Long recipientId, @Param("when") Instant when);
}
