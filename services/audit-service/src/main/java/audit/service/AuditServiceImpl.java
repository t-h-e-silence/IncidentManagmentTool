package audit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import audit.model.AuditAction;
import audit.model.AuditEntityType;
import audit.model.AuditEntry;
import audit.model.AuditEntryView;
import audit.model.AuditRecord;
import audit.model.IncidentEvent;
import audit.model.ProcessedEvent;
import audit.repository.AuditEntryRepository;
import audit.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final AuditEntryRepository entries;
    private final ProcessedEventRepository processed;
    private final Clock clock;

    public AuditServiceImpl(AuditEntryRepository entries, ProcessedEventRepository processed, Clock clock) {
        this.entries = entries;
        this.processed = processed;
        this.clock = clock;
    }

    @Override
    public void recordIncidentEvent(IncidentEvent event) {
        if (processed.existsById(event.eventId())) {
            log.debug("Event {} ({}) was already recorded, skipping the repeat", event.eventId(), event.type());
            return;
        }
        toRecord(event).ifPresentOrElse(
                record -> entries.save(new AuditEntry(record, event.occurredAt())),
                () -> log.warn("Event {} has unknown type {}, nothing recorded", event.eventId(), event.type()));
        processed.save(new ProcessedEvent(event.eventId(), clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntryView> getIncidentTimeline(UUID incidentId) {
        return entries.findByIncidentIdOrderByOccurredAtAsc(incidentId).stream().map(AuditEntry::toView).toList();
    }

    @Override
    public int deleteProcessedBefore(Instant before) {
        return processed.deleteProcessedBefore(before);
    }

    /**
     * The same entries the monolith wrote: one per change, with the same detail keys.
     */
    private static Optional<AuditRecord> toRecord(IncidentEvent event) {
        UUID incidentId = event.incidentId();
        return Optional.ofNullable(switch (event.type()) {
            case IncidentEvent.CREATED -> incident(event, AuditAction.INCIDENT_CREATED,
                    "team", event.incidentText("teamId"), "severity", event.incidentText("severity"));
            case IncidentEvent.DETAILS_UPDATED -> incident(event, AuditAction.DETAILS_UPDATED,
                    "fromTitle", event.text("fromTitle"), "toTitle", event.text("toTitle"),
                    "descriptionChanged", event.text("descriptionChanged"));
            case IncidentEvent.ESCALATED, IncidentEvent.DE_ESCALATED -> incident(event,
                    IncidentEvent.ESCALATED.equals(event.type()) ? AuditAction.ESCALATED : AuditAction.DE_ESCALATED,
                    "fromSeverity", event.text("fromSeverity"), "toSeverity", event.text("toSeverity"),
                    "fromTeam", event.text("fromTeamId"), "toTeam", event.text("toTeamId"),
                    "reason", event.text("reason"));
            case IncidentEvent.STATUS_CHANGED -> incident(event, AuditAction.STATUS_CHANGED,
                    "from", event.text("fromStatus"), "to", event.text("toStatus"), "note", event.text("note"));
            case IncidentEvent.COMMENT_ADDED -> new AuditRecord(event.actor().id(), AuditAction.COMMENT_ADDED,
                    AuditEntityType.COMMENT, UUID.fromString(event.text("commentId")), incidentId, Map.of(),
                    event.correlationId());
            default -> null;
        });
    }

    /**
     * @param details alternating keys and values; null values are left out
     */
    private static AuditRecord incident(IncidentEvent event, AuditAction action, String... details) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < details.length; i += 2) {
            if (details[i + 1] != null) {
                map.put(details[i], details[i + 1]);
            }
        }
        return new AuditRecord(event.actor().id(), action, AuditEntityType.INCIDENT, event.incidentId(),
                event.incidentId(), map, event.correlationId());
    }
}
