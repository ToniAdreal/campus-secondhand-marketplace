package com.toni.marketplace.notification;

import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * In-app notifications for order lifecycle events (backlog #157).
 *
 * <p>Write side: {@link #record} carries no {@code @Transactional} of its
 * own, mirroring {@code AuditService.record} — the repository save joins
 * the caller's transaction, so a transition that rolls back (a declined
 * capture, a 409 race loss) leaves no notification row, and the
 * idempotent replays in {@code OrderService} return before reaching it,
 * so a replay writes no duplicate. {@code OrderService} is the single
 * write point; each transition notifies exactly one party (pay →
 * seller, cancel → seller, complete → buyer, refund → buyer, expiry →
 * buyer).
 *
 * <p>Read side: every read is scoped to the caller's own rows — the
 * recipient id always comes from the JWT principal, never a parameter.
 * Mark-read on a row that is not the caller's (or does not exist) is a
 * plain 404, so the endpoint is no existence oracle.
 *
 * <p>Honest scope: in-app only. No email, no push, no websockets — the
 * row exists when the user next opens the SPA, and nothing is delivered
 * anywhere else.
 */
@Service
public class NotificationService {

  private final NotificationRepository notifications;
  private final Clock clock;

  @Autowired
  public NotificationService(NotificationRepository notifications, Clock clock) {
    this.notifications = notifications;
    this.clock = clock;
  }

  private NotificationService() {
    this.notifications = null;
    this.clock = Clock.systemUTC();
  }

  /**
   * No-op instance for the legacy direct-unit-test constructors of
   * {@code OrderService}, mirroring {@code AuditService.noop()}:
   * hand-built services assert on their own subject, not on
   * notifications. Production always gets the Spring bean.
   */
  public static NotificationService noop() {
    return new NotificationService();
  }

  /**
   * Records one notification inside the caller's transaction (see the
   * class javadoc for the rollback/replay contract). The no-op instance
   * silently drops the row.
   */
  public void record(Long recipientId, NotificationType type, Long orderId) {
    if (notifications == null) {
      return;
    }
    notifications.save(new Notification(recipientId, type, orderId));
  }

  /**
   * Offer-lifecycle variant of {@link #record} (backlog #168): the row
   * carries the offer id and, on acceptance, the created order's id; a
   * decline passes a null order id (no order exists). Same transaction
   * contract as {@link #record} — the save joins the caller's
   * transaction, so a rolled-back accept/decline leaves no row.
   */
  public void recordOffer(Long recipientId, NotificationType type, Long offerId, Long orderId) {
    if (notifications == null) {
      return;
    }
    notifications.save(new Notification(recipientId, type, orderId, offerId));
  }

  /** The caller's own notifications, paginated (controller supplies newest-first). */
  @Transactional(readOnly = true)
  public Page<NotificationDto> list(Long recipientId, Pageable pageable) {
    if (notifications == null) {
      return Page.empty(pageable);
    }
    return notifications.findByRecipientId(recipientId, pageable).map(NotificationDto::from);
  }

  /** The caller's total unread — a bare count for the nav bell, no row data. */
  @Transactional(readOnly = true)
  public long unreadCount(Long recipientId) {
    if (notifications == null) {
      return 0;
    }
    return notifications.countByRecipientIdAndReadAtIsNull(recipientId);
  }

  /**
   * Marks one of the caller's own notifications read. Re-marking is
   * idempotent (the first stamp stands). Someone else's row — or an
   * unknown id — is 404, never 403: existence is not leaked.
   */
  @Transactional
  public NotificationDto markRead(Long recipientId, Long notificationId) {
    if (notifications == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found");
    }
    Notification notification = notifications
        .findByIdAndRecipientId(notificationId, recipientId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "notification not found"));
    notification.markRead(Instant.now(clock));
    return NotificationDto.from(notifications.save(notification));
  }
}
