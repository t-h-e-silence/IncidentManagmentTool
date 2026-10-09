package modules.notifications.model;

/**
 * {@code PENDING -> SENT}, or {@code PENDING -> RETRYING -> ... -> SENT | DEAD_LETTERED}.
 * A dead-lettered notification can be replayed back to {@code PENDING}.
 */
public enum NotificationStatus {
    PENDING,
    RETRYING,
    SENT,
    DEAD_LETTERED
}
