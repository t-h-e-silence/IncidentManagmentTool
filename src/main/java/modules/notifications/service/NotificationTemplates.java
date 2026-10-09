package modules.notifications.service;

import modules.notifications.model.IncidentNotice;
import modules.notifications.model.NotificationReason;

/**
 * Subject and body of regular incident emails. Escalation emails are written by the escalations module.
 */
final class NotificationTemplates {

    private NotificationTemplates() {
    }

    static String subject(IncidentNotice notice) {
        String prefix = "[" + notice.severity() + "] ";
        return switch (notice.reason()) {
            case INCIDENT_CREATED -> prefix + "New incident: " + notice.title();
            case INCIDENT_ACKNOWLEDGED -> prefix + "Your incident is being worked on: " + notice.title();
            case INCIDENT_RESOLVED -> prefix + "Resolved: " + notice.title();
            case INCIDENT_REOPENED -> prefix + "Reopened: " + notice.title();
            case INCIDENT_CANCELLED -> prefix + "Cancelled: " + notice.title();
            case INCIDENT_ESCALATED, INCIDENT_DEESCALATED, INCIDENT_REASSIGNED ->
                    throw new IllegalArgumentException("No template for " + notice.reason());
        };
    }

    static String body(IncidentNotice notice) {
        String intro = switch (notice.reason()) {
            case INCIDENT_CREATED -> "A new incident was reported for team " + notice.teamName() + ".";
            case INCIDENT_ACKNOWLEDGED -> "Team " + notice.teamName() + " has started working on your incident.";
            case INCIDENT_RESOLVED -> "The incident was resolved by team " + notice.teamName() + ".";
            case INCIDENT_REOPENED -> "The incident was reopened; team " + notice.teamName() + " is working on it again.";
            case INCIDENT_CANCELLED -> "The incident was cancelled by team " + notice.teamName() + ".";
            case INCIDENT_ESCALATED, INCIDENT_DEESCALATED, INCIDENT_REASSIGNED ->
                    throw new IllegalArgumentException("No template for " + notice.reason());
        };
        StringBuilder body = new StringBuilder(intro).append("\n\n")
                .append("Title:    ").append(notice.title()).append('\n')
                .append("Severity: ").append(notice.severity()).append('\n')
                .append("Team:     ").append(notice.teamName()).append('\n')
                .append("Incident: ").append(notice.incidentId()).append('\n');
        if (notice.note() != null && !notice.note().isBlank()) {
            String label = notice.reason() == NotificationReason.INCIDENT_RESOLVED
                    ? "Resolution" : "Reason";
            body.append('\n').append(label).append(": ").append(notice.note()).append('\n');
        }
        return body.toString();
    }
}
