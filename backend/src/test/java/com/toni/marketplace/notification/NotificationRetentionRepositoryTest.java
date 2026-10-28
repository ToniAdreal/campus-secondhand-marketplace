package com.toni.marketplace.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

/**
 * Slice tests for the retention query behind the nightly notification
 * purge (backlog #259): selection is by {@code read_at} only — a row is
 * purgeable only once its read marker predates the cut-off, and an
 * unread row (null marker) is never selected, however old, so the
 * purge can never delete a notification its recipient has not opened.
 */
@DataJpaTest
class NotificationRetentionRepositoryTest {

  private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");

  @Autowired
  private NotificationRepository notifications;

  private Notification readAt(Long recipientId, NotificationType type, Instant when) {
    Notification notification = new Notification(recipientId, type, 7L);
    if (when != null) {
      notification.markRead(when);
    }
    return notifications.saveAndFlush(notification);
  }

  @Test
  void selectsOnlyOldReadRows() {
    Notification oldRead = readAt(1L, NotificationType.ORDER_PAID,
        CUTOFF.minusSeconds(1));
    // Recently read: the marker is fresh, so the row survives even
    // though the notification itself may be old.
    readAt(1L, NotificationType.ORDER_SHIPPED, CUTOFF.plusSeconds(3600));
    // Unread: null marker — never selected, regardless of age.
    readAt(2L, NotificationType.ORDER_COMPLETED, null);
    // Exactly at the cut-off: strictly-before, so it survives this
    // run and ages out on a later one.
    readAt(1L, NotificationType.ORDER_REFUNDED, CUTOFF);

    List<Long> ids = notifications.findPurgeableIds(CUTOFF, PageRequest.of(0, 500));

    assertThat(ids).containsExactly(oldRead.getId());
  }

  @Test
  void selectionIgnoresRecipientAndType() {
    // Different recipients, different types, both read long ago:
    // both are selected — there is no predicate by which one
    // person's notification could be spared (or targeted) over
    // another's.
    Notification a = readAt(1L, NotificationType.ORDER_PAID, CUTOFF.minusSeconds(60));
    Notification b = readAt(2L, NotificationType.OFFER_ACCEPTED, CUTOFF.minusSeconds(30));

    List<Long> ids = notifications.findPurgeableIds(CUTOFF, PageRequest.of(0, 500));

    assertThat(ids).containsExactly(a.getId(), b.getId());
  }

  @Test
  void emptyInboxSelectsNothing() {
    assertThat(notifications.findPurgeableIds(CUTOFF, PageRequest.of(0, 500))).isEmpty();
  }
}
