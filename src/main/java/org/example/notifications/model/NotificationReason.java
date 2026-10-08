package org.example.notifications.model;

public enum NotificationReason {
    INCIDENT_CREATED,
    INCIDENT_ACKNOWLEDGED,
    INCIDENT_RESOLVED,
    INCIDENT_REASSIGNED,
    INCIDENT_REOPENED,
    INCIDENT_CANCELLED,
    /** Content built by the escalations module. */
    INCIDENT_ESCALATED,
    /** Content built by the escalations module. */
    INCIDENT_DEESCALATED
}
