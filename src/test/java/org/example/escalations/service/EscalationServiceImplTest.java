package org.example.escalations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.TestData.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.common.model.Severity;
import org.example.escalations.model.Escalation;
import org.example.escalations.model.EscalationDirection;
import org.example.escalations.model.EscalationRecipients;
import org.example.escalations.model.EscalationRecord;
import org.example.escalations.model.EscalationView;
import org.example.escalations.repository.EscalationRepository;
import org.example.notifications.model.EmailMessage;
import org.example.notifications.model.NotificationReason;
import org.example.notifications.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EscalationServiceImplTest {

    @Mock
    EscalationRepository repository;
    @Mock
    NotificationService notifications;

    private EscalationServiceImpl service;
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EscalationServiceImpl(repository, notifications, CLOCK);
    }

    private EscalationRecord record(UUID fromTeam, String fromName, UUID toTeam, String toName) {
        return new EscalationRecord(incidentId, "DB down", DAN.id(), "Dan Dba", "replica lag growing",
                Severity.SEV3, Severity.SEV1, fromTeam, fromName, toTeam, toName);
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, EmailMessage> sentEmails() {
        ArgumentCaptor<List<EmailMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(notifications).send(sent.capture());
        assertThat(sent.getValue()).allMatch(m -> m.reason() == NotificationReason.INCIDENT_ESCALATED
                && m.incidentId().equals(incidentId));
        return sent.getValue().stream().collect(Collectors.toMap(m -> m.recipient().userId(), Function.identity()));
    }

    @Test
    void handOverEmailsEachReceiverDifferentlyAndSkipsTheEscalator() {
        when(repository.save(any(Escalation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EscalationView view = service.recordAndNotify(record(DATABASE, "Database", PLATFORM, "Platform"),
                new EscalationRecipients(List.of(recipient(CAROL)), List.of(recipient(ALICE), recipient(DAN)),
                        Optional.of(recipient(BOB))));

        assertThat(view.fromTeamId()).isEqualTo(DATABASE);
        assertThat(view.toTeamId()).isEqualTo(PLATFORM);
        assertThat(view.escalatedAt()).isEqualTo(NOW);

        Map<UUID, EmailMessage> emails = sentEmails();
        assertThat(emails).containsOnlyKeys(CAROL.id(), ALICE.id(), BOB.id());
        assertThat(emails.get(CAROL.id()).subject()).isEqualTo("[SEV1] Escalated to you: DB down");
        assertThat(emails.get(CAROL.id()).body())
                .contains("Escalated to your team Platform by Dan Dba: replica lag growing", "from SEV3 to SEV1");
        assertThat(emails.get(ALICE.id()).subject()).isEqualTo("[SEV1] Handed over to Platform: DB down");
        assertThat(emails.get(ALICE.id()).body()).contains("no longer owns");
        assertThat(emails.get(BOB.id()).subject()).isEqualTo("[SEV1] Your incident was escalated: DB down");
        assertThat(emails.get(BOB.id()).body()).contains("escalated to SEV1 and is now handled by team Platform");
    }

    @Test
    void sameTeamEscalationEmailsTheTeamAndReporterOnly() {
        when(repository.save(any(Escalation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordAndNotify(record(DATABASE, "Database", DATABASE, "Database"),
                new EscalationRecipients(List.of(recipient(ALICE), recipient(DAN)), List.of(recipient(ALICE)),
                        Optional.of(recipient(BOB))));

        Map<UUID, EmailMessage> emails = sentEmails();
        assertThat(emails).containsOnlyKeys(ALICE.id(), BOB.id());
        assertThat(emails.get(ALICE.id()).subject()).isEqualTo("[SEV1] Escalated: DB down");
        assertThat(emails.get(ALICE.id()).body()).startsWith("Escalated by Dan Dba: replica lag growing");
    }

    @Test
    void reporterInTheOwningTeamGetsOnlyTheTeamEmail() {
        when(repository.save(any(Escalation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordAndNotify(record(DATABASE, "Database", PLATFORM, "Platform"),
                new EscalationRecipients(List.of(recipient(BOB)), List.of(), Optional.of(recipient(BOB))));

        assertThat(sentEmails().get(BOB.id()).subject()).startsWith("[SEV1] Escalated to you");
    }

    @Test
    void deEscalationIsRecordedAndEmailedAsSuch() {
        when(repository.save(any(Escalation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EscalationRecord deEscalation = new EscalationRecord(incidentId, "DB down", DAN.id(), "Dan Dba",
                "only one replica affected", Severity.SEV1, Severity.SEV3, DATABASE, "Database", DATABASE, "Database");

        EscalationView view = service.recordAndNotify(deEscalation,
                new EscalationRecipients(List.of(recipient(ALICE)), List.of(), Optional.of(recipient(BOB))));

        assertThat(view.direction()).isEqualTo(EscalationDirection.DOWN);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(notifications).send(sent.capture());
        Map<UUID, EmailMessage> emails = sent.getValue().stream()
                .collect(Collectors.toMap(m -> m.recipient().userId(), Function.identity()));
        assertThat(emails.values()).allMatch(m -> m.reason() == NotificationReason.INCIDENT_DEESCALATED);
        assertThat(emails.get(ALICE.id()).subject()).isEqualTo("[SEV3] De-escalated: DB down");
        assertThat(emails.get(ALICE.id()).body()).contains("Severity lowered from SEV1 to SEV3");
        assertThat(emails.get(BOB.id()).subject()).isEqualTo("[SEV3] Your incident was de-escalated: DB down");
    }

    @Test
    void severityMustChange() {
        assertThatThrownBy(() -> new EscalationRecord(incidentId, "DB down", DAN.id(), "Dan", "why", Severity.SEV2,
                Severity.SEV2, DATABASE, "Database", DATABASE, "Database"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> new EscalationRecord(incidentId, "DB down", DAN.id(), "Dan", " ", Severity.SEV3,
                Severity.SEV1, DATABASE, "Database", DATABASE, "Database"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
