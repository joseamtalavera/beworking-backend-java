 package com.beworking.notifications;

  /**
   * Lifecycle of a formal notification (must match the CHECK constraint in
   * V91__notifications.sql):
   *   CREATED      — saved, email nudge not yet sent
   *   SENT         — email nudge sent to the client
   *   READ         — client opened it in-app (read_at captured)
   *   ACKNOWLEDGED — client confirmed acuse de recibo (acknowledged_at captured)
   */
  public enum NotificationStatus {
      CREATED,
      SENT,
      READ,
      ACKNOWLEDGED
  }
