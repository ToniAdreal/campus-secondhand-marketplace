package com.toni.marketplace.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository
    extends JpaRepository<NotificationPreference, Long> {

  /** Every explicit toggle of one user — the settings catalogue read (backlog #216). */
  List<NotificationPreference> findByUserId(Long userId);

  /**
   * The one explicit toggle for a (user, type) pair, if any — the
   * recording seam's probe (backlog #216): empty means enabled.
   */
  Optional<NotificationPreference> findByUserIdAndType(Long userId, NotificationType type);
}
