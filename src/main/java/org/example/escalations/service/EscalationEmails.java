package org.example.escalations.service;

import org.example.escalations.model.EscalationDirection;
import org.example.escalations.model.EscalationRecord;

/**
 * Texts of escalation and de-escalation emails, one per kind of receiver.
 */
final class EscalationEmails {

    private EscalationEmails() {
    }

    static String owningTeamSubject(EscalationRecord e) {
        return prefix(e) + verb(e) + (e.handedOver() ? " to you: " : ": ") + e.incidentTitle();
    }

    static String owningTeamBody(EscalationRecord e) {
        String intro = e.handedOver()
                ? verb(e) + " to your team " + e.toTeamName() + " by " + e.actorName() + ": " + e.reason()
                : verb(e) + " by " + e.actorName() + ": " + e.reason();
        return intro + "\nSeverity " + (up(e) ? "raised" : "lowered") + " from " + e.fromSeverity() + " to "
                + e.toSeverity() + ".\n\n" + details(e);
    }

    static String previousTeamSubject(EscalationRecord e) {
        return prefix(e) + "Handed over to " + e.toTeamName() + ": " + e.incidentTitle();
    }

    static String previousTeamBody(EscalationRecord e) {
        return "Handed over from your team " + e.fromTeamName() + " to " + e.toTeamName() + " by " + e.actorName()
                + ": " + e.reason() + "\nYour team no longer owns this incident.\n\n" + details(e);
    }

    static String reporterSubject(EscalationRecord e) {
        return prefix(e) + "Your incident was " + verb(e).toLowerCase() + ": " + e.incidentTitle();
    }

    static String reporterBody(EscalationRecord e) {
        return "Your incident was " + verb(e).toLowerCase() + " to " + e.toSeverity()
                + " and is now handled by team " + e.toTeamName() + ".\n\n" + details(e);
    }

    private static boolean up(EscalationRecord e) {
        return e.direction() == EscalationDirection.UP;
    }

    private static String verb(EscalationRecord e) {
        return up(e) ? "Escalated" : "De-escalated";
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
