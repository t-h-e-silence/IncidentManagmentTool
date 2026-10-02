package org.example.escalations.service;

import java.util.List;
import java.util.UUID;

import org.example.escalations.model.EscalationRecipients;
import org.example.escalations.model.EscalationRecord;
import org.example.escalations.model.EscalationView;

/**
 * Escalation history and escalation emails. The only module that uses another module's service: it builds the
 * emails per receiver and hands them to {@code NotificationService}.
 */
public interface EscalationService {

    /**
     * Stores the escalation and queues the emails: owning team, previous team (if handed over) and reporter
     * each get their own text. Everyone gets at most one email; the person who escalated gets none.
     */
    EscalationView recordAndNotify(EscalationRecord record, EscalationRecipients recipients);

    /**
     * Escalations of an incident, oldest first.
     */
    List<EscalationView> getEscalations(UUID incidentId);
}
