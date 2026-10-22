package com.toni.marketplace.notification;

/**
 * One row of the notification-preference catalogue (backlog #216): the
 * type, its label (owned by {@link NotificationType} — the one place
 * labels live), whether it is a locked-on security type, and the
 * caller's effective state (an absent preference row reads enabled).
 */
public record NotificationPreferenceDto(
    NotificationType type,
    String label,
    boolean security,
    boolean enabled) {

  public static NotificationPreferenceDto of(NotificationType type, boolean enabled) {
    return new NotificationPreferenceDto(
        type, type.label(), type.isSecurity(), type.isSecurity() || enabled);
  }
}
