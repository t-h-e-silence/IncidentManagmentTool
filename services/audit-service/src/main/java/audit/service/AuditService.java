package audit.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import audit.model.AuditEntryView;
import audit.model.IncidentEvent;

/**
 * Append-only record of every significant action, built from incident events (ADR D1, D3).
 */
public interface AuditService {

    /**
     * Appends the entry for one incident event, unless the event was already recorded (RabbitMQ delivers at least
     * once). The entry and the processed-event mark commit together. Unknown event types are skipped.
     */
    void recordIncidentEvent(IncidentEvent event);

    /**
     * Everything that happened to an incident, oldest first; empty if nothing was recorded for it (yet).
     */
    List<AuditEntryView> getIncidentTimeline(UUID incidentId);

    /**
     * @return number of processed-event marks deleted
     */
    int deleteProcessedBefore(Instant before);
}
