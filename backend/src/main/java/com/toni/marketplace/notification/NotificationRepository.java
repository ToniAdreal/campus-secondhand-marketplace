package com.toni.marketplace.notification;

import java.time.Instant;
import java.util.List;
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
   * Ids of read rows whose read marker predates {@code readBefore} —
   * the retention cut-off computed by the nightly purge job (backlog
   * #259). The predicate is {@code read_at} only, and it excludes
   * unread rows by construction ({@code read_at is not null}):
   * an unread notification is never purged, however old, and a
   * recently-read row survives until its read marker ages out.
   * Ordered by id so the batch loop can always re-read the first
   * page without skipping rows. Mirrors
   * {@code RecentlyViewedRepository.findPurgeableIds}, with the
   * read marker playing the viewed_at role.
   */
  @Query("select n.id from Notification n where n.readAt is not null"
      + " and n.readAt < :readBefore order by n.id")
  List<Long> findPurgeableIds(@Param("readBefore") Instant readBefore, Pageable page);

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
