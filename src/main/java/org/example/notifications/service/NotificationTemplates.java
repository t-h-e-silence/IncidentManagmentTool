package org.example.notifications.service;

import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.NotificationReason;

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
            case INCIDENT_REASSIGNED -> prefix + "Incident handed over to " + notice.teamName() + ": " + notice.title();
            case INCIDENT_ESCALATED ->
                    throw new IllegalArgumentException("Escalation emails are built by the escalations module");
        };
    }

    static String body(IncidentNotice notice) {
        String intro = switch (notice.reason()) {
            case INCIDENT_CREATED -> "A new incident was reported for team " + notice.teamName() + ".";
            case INCIDENT_ACKNOWLEDGED -> "Team " + notice.teamName() + " has started working on your incident.";
            case INCIDENT_RESOLVED -> "The incident was resolved by team " + notice.teamName() + ".";
            case INCIDENT_REASSIGNED -> "The incident is now handled by team " + notice.teamName() + ".";
            case INCIDENT_ESCALATED ->
                    throw new IllegalArgumentException("Escalation emails are built by the escalations module");
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
