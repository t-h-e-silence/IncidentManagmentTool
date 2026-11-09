package notifications.model;

/**
 * Why an email is sent. The regular ones are rendered by {@code NotificationTemplates}; escalation emails arrive
 * ready-made in {@code notification.email-requested} commands from escalation-service.
 */
public enum NotificationReason {
    INCIDENT_CREATED,
    INCIDENT_ACKNOWLEDGED,
    INCIDENT_RESOLVED,
    INCIDENT_REOPENED,
    INCIDENT_CANCELLED,
    INCIDENT_ESCALATED,
    INCIDENT_DEESCALATED
}
