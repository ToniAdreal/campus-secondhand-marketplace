package com.toni.marketplace.notification;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
