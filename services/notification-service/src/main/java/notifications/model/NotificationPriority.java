package notifications.model;

/**
 * {@code URGENT} for incidents at SEV1 or SEV2 when the event happened: consumed from {@code notifications.urgent},
 * first SMTP attempt right after the email is stored, sent first by the delivery job (ADR D8). {@code NORMAL}
 * otherwise.
 */
public enum NotificationPriority {
    URGENT,
    NORMAL
}
