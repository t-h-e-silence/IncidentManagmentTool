package escalations.service;

import java.time.Instant;

import escalations.model.IncidentEvent;

/**
 * Records escalations and de-escalations and writes their emails (ADR D2). The decision itself (severity up or
 * down, hand-over, target team active) was made atomically in incident-service; this service only reacts to it.
 */
public interface EscalationService {

    /**
     * Stores the escalation record and queues one {@code notification.email-requested} command per email, unless the
     * event was already recorded (RabbitMQ delivers at least once). Record, commands and processed-event mark commit
     * together. The owning team, the previous team (if handed over) and the reporter each get their own text;
     * everyone gets at most one email; the person who escalated gets none.
     */
    void record(IncidentEvent event);

    /**
     * @return number of processed-event marks deleted
     */
    int deleteProcessedBefore(Instant before);
}
