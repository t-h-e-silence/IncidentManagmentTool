package audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import audit.model.AuditAction;
import audit.model.AuditEntityType;
import audit.model.AuditEntry;
import audit.model.AuditEntryView;
import audit.model.IncidentEvent;
import audit.model.ProcessedEvent;
import audit.repository.AuditEntryRepository;
import audit.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Each incident event becomes the audit entry the monolith wrote for that change, with the same detail keys.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-10-02T09:59:59Z");
    private static final UUID DAN = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID DATABASE = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID PLATFORM = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Mock
    AuditEntryRepository entries;
    @Mock
    ProcessedEventRepository processed;

    private AuditServiceImpl audit;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        audit = new AuditServiceImpl(entries, processed, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * An event as it arrives from RabbitMQ: incident-service's JSON envelope, read into this service's class.
     */
    private IncidentEvent event(String type, String data) throws Exception {
        return json.readValue("""
                {"eventId": "%s", "type": "%s", "version": 1, "occurredAt": "%s", "correlationId": "corr-1",
                 "actor": {"id": "%s", "name": "Dan Dba"}, "incidentId": "%s",
                 "data": {"incident": {"id": "%s", "teamId": "%s", "severity": "SEV2", "status": "OPEN"}%s},
                 "addedLater": "ignored"}"""
                .formatted(UUID.randomUUID(), type, OCCURRED, DAN, incidentId, incidentId, DATABASE,
                        data.isEmpty() ? "" : ", " + data), IncidentEvent.class);
    }

    private AuditEntryView recorded(IncidentEvent event) {
        audit.recordIncidentEvent(event);
        ArgumentCaptor<AuditEntry> saved = ArgumentCaptor.forClass(AuditEntry.class);
        verify(entries).save(saved.capture());
        verify(processed).save(any(ProcessedEvent.class));
        return saved.getValue().toView();
    }

    @Test
    void createdKeepsTeamAndSeverityAndTheEventTime() throws Exception {
        AuditEntryView entry = recorded(event("incident.created", ""));

        assertThat(entry.action()).isEqualTo(AuditAction.INCIDENT_CREATED);
        assertThat(entry.entityType()).isEqualTo(AuditEntityType.INCIDENT);
        assertThat(entry.entityId()).isEqualTo(incidentId);
        assertThat(entry.actorId()).isEqualTo(DAN);
        assertThat(entry.correlationId()).isEqualTo("corr-1");
        assertThat(entry.occurredAt()).isEqualTo(OCCURRED);
        assertThat(entry.details()).isEqualTo(Map.of("team", DATABASE.toString(), "severity", "SEV2"));
    }

    @Test
    void escalationKeepsFromToAndReason() throws Exception {
        AuditEntryView entry = recorded(event("incident.escalated", """
                "fromSeverity": "SEV3", "toSeverity": "SEV1", "fromTeamId": "%s", "toTeamId": "%s",
                "reason": "replica lag" """.formatted(DATABASE, PLATFORM)));

        assertThat(entry.action()).isEqualTo(AuditAction.ESCALATED);
        assertThat(entry.details()).isEqualTo(Map.of("fromSeverity", "SEV3", "toSeverity", "SEV1",
                "fromTeam", DATABASE.toString(), "toTeam", PLATFORM.toString(), "reason", "replica lag"));
    }

    @Test
    void statusChangeLeavesOutAMissingNote() throws Exception {
        AuditEntryView entry = recorded(event("incident.status-changed", """
                "fromStatus": "OPEN", "toStatus": "IN_PROGRESS", "note": null"""));

        assertThat(entry.action()).isEqualTo(AuditAction.STATUS_CHANGED);
        assertThat(entry.details()).isEqualTo(Map.of("from", "OPEN", "to", "IN_PROGRESS"));
    }

    @Test
    void detailsUpdateKeepsBothTitles() throws Exception {
        assertThat(recorded(event("incident.details-updated", """
                "fromTitle": "Slow", "toTitle": "Primary DB slow", "descriptionChanged": false""")).details())
                .isEqualTo(Map.of("fromTitle", "Slow", "toTitle", "Primary DB slow", "descriptionChanged", "false"));
    }

    @Test
    void commentIsRecordedOnTheComment() throws Exception {
        UUID commentId = UUID.randomUUID();

        AuditEntryView entry = recorded(event("incident.comment-added", """
                "commentId": "%s", "text": "Also on mobile" """.formatted(commentId)));

        assertThat(entry.action()).isEqualTo(AuditAction.COMMENT_ADDED);
        assertThat(entry.entityType()).isEqualTo(AuditEntityType.COMMENT);
        assertThat(entry.entityId()).isEqualTo(commentId);
        assertThat(entry.incidentId()).isEqualTo(incidentId);
        assertThat(entry.details()).isEmpty();
    }

    @Test
    void repeatedEventIsSkipped() throws Exception {
        IncidentEvent event = event("incident.created", "");
        when(processed.existsById(event.eventId())).thenReturn(true);

        audit.recordIncidentEvent(event);

        verify(entries, never()).save(any());
        verify(processed, never()).save(any());
    }

    @Test
    void unknownTypeIsMarkedProcessedWithoutAnEntry() throws Exception {
        audit.recordIncidentEvent(event("incident.archived", ""));

        verify(entries, never()).save(any());
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void timelineIsEmptyWhenNothingWasRecorded() {
        when(entries.findByIncidentIdOrderByOccurredAtAsc(incidentId)).thenReturn(List.of());

        assertThat(audit.getIncidentTimeline(incidentId)).isEmpty();
    }
}
