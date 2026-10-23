package com.toni.marketplace.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * <p>Honest scope: in-app only. No email, no websockets. Since backlog
 * #203 a recorded row also fans a minimal {@code {type, id}} hint out
 * over the SSE stream (see {@link NotificationStreamService}) — but the
 * stream is only a hint: it is published after the recording
 * transaction commits (a rolled-back transition notifies nobody), and
 * the SPA refetches the real rows over this service's authenticated
 * reads, which stay the fallback whenever the stream is down.
 *
 * <p>Per-type preferences (backlog #216): every {@code record*} method
 * below is the single write seam for its variant, and each one first
 * asks {@link #isEnabledFor} — a type the recipient muted writes no row
 * and publishes no SSE hint, and no producer can bypass the check
 * because producers only ever call these methods. Security types
 * ({@link NotificationType#isSecurity()}) are never muted: the check
 * answers enabled for them unconditionally.
 */
@Service
public class NotificationService {

  private final NotificationRepository notifications;
  private final Clock clock;
  private final NotificationStreamService streams;
  private final NotificationPreferenceRepository preferences;

  @Autowired
  public NotificationService(NotificationRepository notifications, Clock clock,
                             NotificationStreamService streams,
                             NotificationPreferenceRepository preferences) {
    this.notifications = notifications;
    this.clock = clock;
    this.streams = streams;
    this.preferences = preferences;
  }

  private NotificationService() {
    this.notifications = null;
    this.clock = Clock.systemUTC();
    this.streams = null;
    this.preferences = null;
  }

  /**
   * Whether a notification of {@code type} may be recorded for
   * {@code recipientId} (backlog #216). Security types are always
   * enabled; an absent preference row is enabled (the default every
   * existing user starts from); only an explicit muted row disables.
   * On the no-op instance there is no store to consult, so everything
   * reads enabled — the record methods have already returned by then.
   */
  private boolean isEnabledFor(Long recipientId, NotificationType type) {
    if (type.isSecurity() || preferences == null) {
      return true;
    }
    return preferences.findByUserIdAndType(recipientId, type)
        .map(NotificationPreference::isEnabled)
        .orElse(true);
  }

  /**
   * Fans the SSE hint out for a saved row (backlog #203). Runs after
   * the recording transaction commits — registered as an after-commit
   * callback when a transaction synchronization is active, immediately
   * otherwise (direct unit-test calls) — so a rolled-back transition
   * never produces a phantom event. On the no-op instance {@code
   * streams} is null and every record method has already returned.
   */
  private void publishAfterCommit(Notification saved) {
    if (streams == null) {
      return;
    }
    Runnable publish = () -> streams.publish(
        saved.getRecipientId(), saved.getType(), saved.getId());
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  publish.run();
                }
              });
    } else {
      publish.run();
    }
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
    if (notifications == null || !isEnabledFor(recipientId, type)) {
      return;
    }
    publishAfterCommit(notifications.save(new Notification(recipientId, type, orderId)));
  }

  /**
   * Offer-lifecycle variant of {@link #record} (backlog #168): the row
   * carries the offer id and, on acceptance, the created order's id; a
   * decline passes a null order id (no order exists). Same transaction
   * contract as {@link #record} — the save joins the caller's
   * transaction, so a rolled-back accept/decline leaves no row.
   */
  public void recordOffer(Long recipientId, NotificationType type, Long offerId, Long orderId) {
    if (notifications == null || !isEnabledFor(recipientId, type)) {
      return;
    }
    publishAfterCommit(notifications.save(new Notification(recipientId, type, orderId, offerId)));
  }

  /**
   * Saved-search match variant (backlog #182): one batched row per
   * saved search per matcher run, carrying the search id and how many
   * new listings matched. Same transaction contract as {@link #record}
   * — the save joins the matcher run's transaction, so a failed run
   * writes no row and advances no watermark.
   */
  public void recordSavedSearchMatch(Long recipientId, Long savedSearchId,
                                     int matchedListingCount) {
    if (notifications == null
        || !isEnabledFor(recipientId, NotificationType.SAVED_SEARCH_MATCH)) {
      return;
    }
    publishAfterCommit(notifications.save(
        Notification.savedSearchMatch(recipientId, savedSearchId, matchedListingCount)));
  }

  /**
   * Favorite price-drop variant (backlog #189): one row per favoriter
   * whose price snapshot is above the listing's new price, carrying
   * the listing id and both prices. Same transaction contract as
   * {@link #record} — the save joins the price edit's transaction, so
   * a rolled-back (or 409-losing) edit writes no row and advances no
   * snapshot.
   */
  public void recordFavoritePriceDrop(Long recipientId, Long itemId,
                                      long oldPriceCents, long newPriceCents) {
    if (notifications == null
        || !isEnabledFor(recipientId, NotificationType.FAVORITE_PRICE_DROP)) {
      return;
    }
    publishAfterCommit(notifications.save(
        Notification.favoritePriceDrop(recipientId, itemId, oldPriceCents, newPriceCents)));
  }

  /**
   * Report-appeal resolution variant (backlog #190): one row for the
   * appealing seller per resolution, carrying the appeal id. Same
   * transaction contract as {@link #record} — the save joins the
   * resolution's transaction, so a rolled-back (or 409-losing)
   * resolution writes no row, and the idempotent re-resolve returns
   * before reaching here.
   */
  public void recordReportAppeal(Long recipientId, NotificationType type, Long appealId) {
    if (notifications == null || !isEnabledFor(recipientId, type)) {
      return;
    }
    publishAfterCommit(notifications.save(Notification.reportAppeal(recipientId, type, appealId)));
  }

  /**
   * New-listing-from-a-followed-seller variant (backlog #217): one row
   * per follower of the publishing seller, carrying the listing id in
   * the same column as the favorite-price-drop rows. Preference-aware
   * like every other variant — a recipient who muted this type gets no
   * row. Same transaction contract as {@link #record} — the save joins
   * the publish transaction, so a rolled-back publish writes no rows.
   */
  public void recordSellerNewListing(Long recipientId, Long itemId) {
    if (notifications == null
        || !isEnabledFor(recipientId, NotificationType.SELLER_NEW_LISTING)) {
      return;
    }
    publishAfterCommit(notifications.save(Notification.sellerNewListing(recipientId, itemId)));
  }

  /**
   * The full preference catalogue for the caller (backlog #216): one
   * row per {@link NotificationType}, in enum order, with the caller's
   * effective state — an absent stored row reads enabled, and security
   * types always read enabled (see {@link NotificationPreferenceDto#of}).
   */
  @Transactional(readOnly = true)
  public List<NotificationPreferenceDto> listPreferences(Long userId) {
    Map<NotificationType, Boolean> stored = new EnumMap<>(NotificationType.class);
    if (preferences != null) {
      for (NotificationPreference row : preferences.findByUserId(userId)) {
        stored.put(row.getType(), row.isEnabled());
      }
    }
    return java.util.Arrays.stream(NotificationType.values())
        .map(type -> NotificationPreferenceDto.of(type, stored.getOrDefault(type, true)))
        .toList();
  }

  /**
   * Applies one settings toggle (backlog #216) and returns the type's
   * new catalogue row. Muting a security type is 422 — those alerts are
   * locked on, and the settings UI renders them that way, so a 422 here
   * means a hand-built request. Enabling a security type is a no-op
   * success (it was never off; no row is written). Any other toggle is
   * an upsert on the (user, type) pair, so repeat toggles never create
   * duplicate rows.
   */
  @Transactional
  public NotificationPreferenceDto setPreference(Long userId, NotificationType type,
                                                 boolean enabled) {
    if (type.isSecurity()) {
      if (!enabled) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "security notifications cannot be turned off");
      }
      return NotificationPreferenceDto.of(type, true);
    }
    if (preferences == null) {
      return NotificationPreferenceDto.of(type, enabled);
    }
    NotificationPreference row = preferences.findByUserIdAndType(userId, type)
        .orElseGet(() -> new NotificationPreference(userId, type, enabled, Instant.now(clock)));
    row.setEnabled(enabled, Instant.now(clock));
    preferences.save(row);
    return NotificationPreferenceDto.of(type, enabled);
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

  /**
   * Marks every unread notification of the caller read in one update
   * (backlog #173) and returns how many rows were stamped. Idempotent:
   * a second call stamps nothing and returns 0, never an error. On the
   * no-op instance there is nothing to stamp, so it returns 0.
   */
  @Transactional
  public int markAllRead(Long recipientId) {
    if (notifications == null) {
      return 0;
    }
    return notifications.markAllRead(recipientId, Instant.now(clock));
  }

  /**
   * Deletes one of the caller's own notifications (backlog #173).
   * Someone else's row — or an unknown id — is 404, never 403: the
   * endpoint is no existence oracle, mirroring {@link #markRead}.
   * Deleting an unread row removes it from the unread count, which is
   * a plain count over live rows, so the bell stays consistent.
   */
  @Transactional
  public void delete(Long recipientId, Long notificationId) {
    if (notifications == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found");
    }
    Notification notification = notifications
        .findByIdAndRecipientId(notificationId, recipientId)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "notification not found"));
    notifications.delete(notification);
  }
}
