package notifications.model;

/**
 * {@code PENDING -> SENT}, or {@code PENDING -> RETRYING -> ... -> SENT | DEAD_LETTERED}.
 */
public enum NotificationStatus {
    PENDING,
    RETRYING,
    SENT,
    DEAD_LETTERED
}
