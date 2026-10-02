package org.example.escalations.service;

import org.example.escalations.model.EscalationRecord;

/**
 * Texts of escalation emails, one per kind of receiver.
 */
final class EscalationEmails {

    private EscalationEmails() {
    }

    static String owningTeamSubject(EscalationRecord e) {
        return prefix(e) + (e.handedOver() ? "Escalated to you: " : "Escalated: ") + e.incidentTitle();
    }

    static String owningTeamBody(EscalationRecord e) {
        String intro = e.handedOver()
                ? "Escalated to your team " + e.toTeamName() + " by " + e.actorName() + ": " + e.reason()
                : "Escalated by " + e.actorName() + ": " + e.reason();
        return intro + "\nSeverity raised from " + e.fromSeverity() + " to " + e.toSeverity() + ".\n\n" + details(e);
    }

    static String previousTeamSubject(EscalationRecord e) {
        return prefix(e) + "Handed over to " + e.toTeamName() + ": " + e.incidentTitle();
    }

    static String previousTeamBody(EscalationRecord e) {
        return "Handed over from your team " + e.fromTeamName() + " to " + e.toTeamName() + " by " + e.actorName()
                + ": " + e.reason() + "\nYour team no longer owns this incident.\n\n" + details(e);
    }

    static String reporterSubject(EscalationRecord e) {
        return prefix(e) + "Your incident was escalated: " + e.incidentTitle();
    }

    static String reporterBody(EscalationRecord e) {
        return "Your incident was escalated to " + e.toSeverity() + " and is now handled by team " + e.toTeamName()
                + ".\n\n" + details(e);
    }

    private static String prefix(EscalationRecord e) {
        return "[" + e.toSeverity() + "] ";
    }

    private static String details(EscalationRecord e) {
        return "Title:    " + e.incidentTitle() + "\n"
                + "Severity: " + e.toSeverity() + "\n"
                + "Team:     " + e.toTeamName() + "\n"
                + "Incident: " + e.incidentId() + "\n";
    }
}
