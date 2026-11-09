package escalations.service;

import static escalations.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import escalations.exception.NotFoundException;
import escalations.model.EmailRequested;
import escalations.model.Escalation;
import escalations.model.IncidentEvent;
import escalations.model.ProcessedEvent;
import escalations.model.Recipient;
import escalations.model.Team;
import escalations.repository.EscalationRepository;
import escalations.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The monolith's escalation emails, now from incident events to {@code notification.email-requested} commands.
 */
@ExtendWith(MockitoExtension.class)
class EscalationServiceImplTest {

    @Mock
    EscalationRepository repository;
    @Mock
    ProcessedEventRepository processed;
    @Mock
    OrganizationClient organization;
    @Mock
    OutboxService outbox;

    private EscalationServiceImpl service;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EscalationServiceImpl(repository, processed, organization, outbox, CLOCK);
        lenient().when(organization.getTeam(DATABASE)).thenReturn(team(DATABASE, "Database", ALICE, DAN));
        lenient().when(organization.getTeam(PLATFORM)).thenReturn(team(PLATFORM, "Platform", CAROL));
        lenient().when(organization.findRecipient(BOB.userId())).thenReturn(Optional.of(BOB));
    }

    /**
     * An escalation event as incident-service publishes it, by Dan unless said otherwise, reported by Bob.
     */
    private IncidentEvent event(String type, String from, String to, UUID fromTeam, UUID toTeam, UUID actor)
            throws Exception {
        return json.readValue("""
                {"eventId": "%s", "type": "%s", "version": 1, "occurredAt": "%s", "correlationId": "corr-1",
                 "actor": {"id": "%s", "name": "Dan Dba"}, "incidentId": "%s",
                 "data": {"incident": {"id": "%s", "title": "DB down", "teamId": "%s", "reporterId": "%s",
                                       "severity": "%s", "status": "OPEN"},
                          "fromSeverity": "%s", "toSeverity": "%s", "fromTeamId": "%s", "toTeamId": "%s",
                          "reason": "replica lag growing"}}"""
                .formatted(UUID.randomUUID(), type, NOW, actor, incidentId, incidentId, toTeam, BOB.userId(), to,
                        from, to, fromTeam, toTeam), IncidentEvent.class);
    }

    private IncidentEvent escalation(UUID fromTeam, UUID toTeam) throws Exception {
        return event("incident.escalated", "SEV3", "SEV1", fromTeam, toTeam, DAN.userId());
    }

    private Map<UUID, EmailRequested> requested() {
        ArgumentCaptor<EmailRequested> sent = ArgumentCaptor.forClass(EmailRequested.class);
        verify(outbox, atLeast(0)).publish(sent.capture());
        return sent.getAllValues().stream()
                .collect(Collectors.toMap(c -> ((Recipient) c.data().get("recipient")).userId(), Function.identity()));
    }

    private static String subject(EmailRequested command) {
        return (String) command.data().get("subject");
    }

    private static String body(EmailRequested command) {
        return (String) command.data().get("body");
    }

    @Test
    void handOverEmailsEachReceiverDifferentlyAndSkipsTheEscalator() throws Exception {
        service.record(escalation(DATABASE, PLATFORM));

        verify(repository).save(any(Escalation.class));
        verify(processed).save(any(ProcessedEvent.class));
        Map<UUID, EmailRequested> emails = requested();
        assertThat(emails).containsOnlyKeys(CAROL.userId(), ALICE.userId(), BOB.userId());
        assertThat(subject(emails.get(CAROL.userId()))).isEqualTo("[SEV1] Escalated to you: DB down");
        assertThat(body(emails.get(CAROL.userId())))
                .contains("Escalated to your team Platform by Dan Dba: replica lag growing", "from SEV3 to SEV1");
        assertThat(subject(emails.get(ALICE.userId()))).isEqualTo("[SEV1] Handed over to Platform: DB down");
        assertThat(body(emails.get(ALICE.userId()))).contains("no longer owns");
        assertThat(subject(emails.get(BOB.userId()))).isEqualTo("[SEV1] Your incident was escalated: DB down");
        assertThat(body(emails.get(BOB.userId()))).contains("escalated to SEV1 and is now handled by team Platform");
        assertThat(emails.values()).allSatisfy(command -> {
            assertThat(command.correlationId()).isEqualTo("corr-1");
            assertThat(command.incidentId()).isEqualTo(incidentId);
            assertThat(command.data()).containsEntry("reason", "INCIDENT_ESCALATED");
            assertThat(command.routingKey()).isEqualTo("notification.email-requested.urgent");
        });
    }

    @Test
    void sameTeamEscalationEmailsTheTeamAndReporterOnly() throws Exception {
        service.record(escalation(DATABASE, DATABASE));

        Map<UUID, EmailRequested> emails = requested();
        assertThat(emails).containsOnlyKeys(ALICE.userId(), BOB.userId());
        assertThat(subject(emails.get(ALICE.userId()))).isEqualTo("[SEV1] Escalated: DB down");
        assertThat(body(emails.get(ALICE.userId()))).startsWith("Escalated by Dan Dba: replica lag growing");
    }

    @Test
    void reporterInTheOwningTeamGetsOnlyTheTeamEmail() throws Exception {
        when(organization.getTeam(PLATFORM)).thenReturn(team(PLATFORM, "Platform", BOB));

        service.record(escalation(DATABASE, PLATFORM));

        assertThat(subject(requested().get(BOB.userId()))).startsWith("[SEV1] Escalated to you");
    }

    @Test
    void reporterWhoEscalatesGetsNothing() throws Exception {
        service.record(event("incident.escalated", "SEV3", "SEV1", DATABASE, DATABASE, BOB.userId()));

        assertThat(requested()).doesNotContainKey(BOB.userId());
        verify(organization, never()).findRecipient(any());
    }

    @Test
    void deEscalationIsRecordedAndEmailedAsSuch() throws Exception {
        service.record(event("incident.de-escalated", "SEV1", "SEV3", DATABASE, DATABASE, DAN.userId()));

        Map<UUID, EmailRequested> emails = requested();
        assertThat(emails.values()).allSatisfy(command -> {
            assertThat(command.data()).containsEntry("reason", "INCIDENT_DEESCALATED");
            assertThat(command.routingKey()).isEqualTo("notification.email-requested.normal");
        });
        assertThat(subject(emails.get(ALICE.userId()))).isEqualTo("[SEV3] De-escalated: DB down");
        assertThat(body(emails.get(ALICE.userId()))).contains("Severity lowered from SEV1 to SEV3");
        assertThat(subject(emails.get(BOB.userId()))).isEqualTo("[SEV3] Your incident was de-escalated: DB down");
    }

    @Test
    void owningTeamWithoutMembersFallsBackToTheAdmins() throws Exception {
        when(organization.getTeam(PLATFORM)).thenReturn(new Team(PLATFORM, "Platform", false, List.of()));
        when(organization.getActiveAdmins()).thenReturn(List.of(ADA));

        service.record(escalation(DATABASE, PLATFORM));

        assertThat(requested()).containsKey(ADA.userId());
    }

    @Test
    void unknownTeamStillRecordsTheEscalation() throws Exception {
        when(organization.getTeam(PLATFORM)).thenThrow(new NotFoundException("Team not found"));
        when(organization.getActiveAdmins()).thenReturn(List.of(ADA));

        service.record(escalation(DATABASE, PLATFORM));

        verify(repository).save(any(Escalation.class));
        assertThat(requested()).containsKeys(ADA.userId(), ALICE.userId(), BOB.userId());
    }

    @Test
    void repeatedEventIsSkipped() throws Exception {
        IncidentEvent event = escalation(DATABASE, PLATFORM);
        when(processed.existsById(event.eventId())).thenReturn(true);

        service.record(event);

        verify(repository, never()).save(any());
        verify(outbox, never()).publish(any());
        verify(processed, never()).save(any());
    }
}
