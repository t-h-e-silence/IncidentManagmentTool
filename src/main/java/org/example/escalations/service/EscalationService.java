package org.example.escalations.service;

import org.example.escalations.model.EscalationRecipients;
import org.example.escalations.model.EscalationRecord;

/**
 * Records escalations and de-escalations and sends their emails. The only module that uses another module's service: it builds the
 * emails per receiver and hands them to {@code NotificationService}.
 */
public interface EscalationService {

    /**
     * Stores the escalation (or de-escalation, see {@link EscalationRecord#direction()}) and queues the emails: owning team, previous team (if handed over) and reporter
     * each get their own text. Everyone gets at most one email; the person who escalated gets none.
     */
    void recordAndNotify(EscalationRecord record, EscalationRecipients recipients);

}
