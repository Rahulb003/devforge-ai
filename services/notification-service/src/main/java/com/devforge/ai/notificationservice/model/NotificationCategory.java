package com.devforge.ai.notificationservice.model;

/**
 * What kind of thing happened, from the recipient's point of view.
 *
 * <p>Coarse on purpose. The category exists so the UI can group and filter, and so a user can
 * later choose to mute work noise without muting security alerts — which is the one distinction
 * that must never collapse. The precise cause is kept separately in {@code eventType}.
 */
public enum NotificationCategory {

  /** Something happened to the account itself: password changed, MFA turned off, sessions revoked. */
  SECURITY,

  /** Work assigned to or affecting the recipient. */
  TASK,

  /** Membership and project lifecycle. */
  PROJECT,

  /** Platform-level messages not caused by a specific domain event. */
  SYSTEM
}
