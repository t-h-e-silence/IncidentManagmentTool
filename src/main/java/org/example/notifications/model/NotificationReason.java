package org.example.notifications.model;

public enum NotificationReason {
    INCIDENT_CREATED,
    INCIDENT_ACKNOWLEDGED,
    INCIDENT_RESOLVED,
    INCIDENT_REASSIGNED,
    /** Content built by the escalations module. */
    INCIDENT_ESCALATED
}
