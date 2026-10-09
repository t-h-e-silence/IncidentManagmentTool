package modules.notifications.model;

public enum NotificationReason {
    INCIDENT_CREATED,
    INCIDENT_ACKNOWLEDGED,
    INCIDENT_RESOLVED,
    /** No longer sent; kept so stored notifications still load. */
    INCIDENT_REASSIGNED,
    INCIDENT_REOPENED,
    INCIDENT_CANCELLED,
    /** Content built by the escalations module. */
    INCIDENT_ESCALATED,
    /** Content built by the escalations module. */
    INCIDENT_DEESCALATED
}
