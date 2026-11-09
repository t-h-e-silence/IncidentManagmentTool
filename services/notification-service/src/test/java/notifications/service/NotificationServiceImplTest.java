package notifications.service;

import static notifications.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import notifications.exception.NotFoundException;
import notifications.model.EmailMessage;
import notifications.model.Notification;
import notifications.model.NotificationMessage;
import notifications.model.NotificationPriority;
import notifications.model.NotificationReason;
import notifications.model.NotificationStatus;
import notifications.model.ProcessedEvent;
import notifications.model.Recipient;
import notifications.model.Team;
import notifications.repository.NotificationRepository;
import notifications.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

/**
 * Who gets which email for each message: the monolith's rules, now driven by incident events and escalation-service
 * commands; plus repeats, and delivery.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    NotificationRepository repository;
    @Mock
    ProcessedEventRepository processed;
    @Mock
    OrganizationClient organization;
    @Mock
    EmailSender emailSender;

    private NotificationServiceImpl service;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UUID incidentId = UUID.fromString("40000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(repository, processed, organization, emailSender, CLOCK, 50);
        lenient().when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(organization.getTeam(DATABASE)).thenReturn(DATABASE_TEAM);
        lenient().when(organization.findRecipient(BOB.userId())).thenReturn(Optional.of(BOB));
    }

    /**
     * A message as it arrives from RabbitMQ: the JSON envelope, read into this service's class.
     *
     * @param actor who made the change
     * @param data  the type's own {@code data} fields, after {@code incident}
     */
    private NotificationMessage message(String type, UUID actor, String data) throws Exception {
        return json.readValue("""
                {"eventId": "%s", "type": "%s", "version": 1, "occurredAt": "%s", "correlationId": "corr-1",
                 "actor": {"id": "%s", "name": "someone"}, "incidentId": "%s",
                 "data": {"incident": {"id": "%s", "title": "Checkout is slow", "teamId": "%s",
                                       "reporterId": "%s", "severity": "SEV2", "status": "OPEN"}%s},
                 "addedLater": "ignored"}"""
                .formatted(UUID.randomUUID(), type, NOW, actor, incidentId, incidentId, DATABASE, BOB.userId(),
                        data.isEmpty() ? "" : ", " + data), NotificationMessage.class);
    }

    private NotificationMessage statusChange(UUID actor, String from, String to, String note) throws Exception {
        return message("incident.status-changed", actor, """
                "fromStatus": "%s", "toStatus": "%s", "note": %s"""
                .formatted(from, to, note == null ? "null" : "\"" + note + "\""));
    }

    @SuppressWarnings("unchecked")
    private List<Notification> stored() {
        ArgumentCaptor<List<Notification>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        return saved.getValue();
    }

    @Test
    void createdGoesToTheTeam() throws Exception {
        List<UUID> ids = service.accept(message("incident.created", BOB.userId(), ""), NotificationPriority.URGENT);

        List<Notification> stored = stored();
        assertThat(stored).extracting(Notification::getRecipientEmail)
                .containsExactly("alice@example.com", "dan@example.com");
        assertThat(stored).allSatisfy(notification -> {
            assertThat(notification.getSubject()).isEqualTo("[SEV2] New incident: Checkout is slow");
            assertThat(notification.getBody()).contains("reported for team Database");
            assertThat(notification.getPriority()).isEqualTo(NotificationPriority.URGENT);
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        });
        assertThat(ids).containsExactlyElementsOf(stored.stream().map(Notification::getId).toList());
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void teamWithoutMembersFallsBackToTheAdmins() throws Exception {
        when(organization.getTeam(DATABASE)).thenReturn(new Team(DATABASE, "Database", false, List.of()));
        when(organization.getActiveAdmins()).thenReturn(List.of(ADA));

        service.accept(message("incident.created", BOB.userId(), ""), NotificationPriority.NORMAL);

        assertThat(stored()).extracting(Notification::getRecipientEmail).containsExactly("ada.admin@example.com");
    }

    @Test
    void unknownTeamAlsoGoesToTheAdmins() throws Exception {
        when(organization.getTeam(DATABASE)).thenThrow(new NotFoundException("Team not found"));
        when(organization.getActiveAdmins()).thenReturn(List.of(ADA));

        service.accept(message("incident.created", BOB.userId(), ""), NotificationPriority.NORMAL);

        assertThat(stored()).extracting(Notification::getRecipientEmail).containsExactly("ada.admin@example.com");
    }

    @Test
    void acknowledgedGoesToTheReporterOnly() throws Exception {
        service.accept(statusChange(DAN.userId(), "OPEN", "IN_PROGRESS", null), NotificationPriority.NORMAL);

        List<Notification> stored = stored();
        assertThat(stored).extracting(Notification::getRecipientEmail).containsExactly("bob@example.com");
        assertThat(stored.get(0).getSubject()).isEqualTo("[SEV2] Your incident is being worked on: Checkout is slow");
    }

    @Test
    void resolvedGoesToTeamAndReporterWithTheNote() throws Exception {
        service.accept(statusChange(DAN.userId(), "IN_REVIEW", "RESOLVED", "index rebuilt"),
                NotificationPriority.NORMAL);

        List<Notification> stored = stored();
        assertThat(stored).extracting(Notification::getRecipientEmail)
                .containsExactly("alice@example.com", "dan@example.com", "bob@example.com");
        assertThat(stored.get(0).getBody()).contains("Resolution: index rebuilt");
    }

    @Test
    void reopenedAndCancelledGoToTeamAndReporter() throws Exception {
        service.accept(statusChange(DAN.userId(), "RESOLVED", "IN_PROGRESS", "came back"), NotificationPriority.NORMAL);
        service.accept(statusChange(DAN.userId(), "OPEN", "CANCELLED", "duplicate"), NotificationPriority.NORMAL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveAll(saved.capture());
        assertThat(saved.getAllValues().get(0).get(0).getSubject()).startsWith("[SEV2] Reopened:");
        assertThat(saved.getAllValues().get(1).get(0).getSubject()).startsWith("[SEV2] Cancelled:");
        assertThat(saved.getAllValues()).allSatisfy(list -> assertThat(list).hasSize(3));
    }

    @Test
    void reporterIsNotEmailedAboutTheirOwnActionNorTwice() throws Exception {
        Team withBob = new Team(DATABASE, "Database", false, List.of(
                new Team.Member(BOB.userId(), BOB.name(), BOB.email()),
                new Team.Member(DAN.userId(), DAN.name(), DAN.email())));
        when(organization.getTeam(DATABASE)).thenReturn(withBob);

        service.accept(statusChange(DAN.userId(), "IN_REVIEW", "RESOLVED", "fixed"), NotificationPriority.NORMAL);

        assertThat(stored()).extracting(Notification::getRecipientEmail)
                .containsExactly("bob@example.com", "dan@example.com");
    }

    @Test
    void reporterAcknowledgingTheirOwnIncidentGetsNothing() throws Exception {
        service.accept(statusChange(BOB.userId(), "OPEN", "IN_PROGRESS", null), NotificationPriority.NORMAL);

        assertThat(stored()).isEmpty();
        verify(organization, never()).findRecipient(any());
    }

    @Test
    void reviewAndClosingSendNothing() throws Exception {
        service.accept(statusChange(DAN.userId(), "IN_PROGRESS", "IN_REVIEW", null), NotificationPriority.NORMAL);

        assertThat(stored()).isEmpty();
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void emailRequestedIsStoredAsItCame() throws Exception {
        NotificationMessage command = message("notification.email-requested", DAN.userId(), """
                "reason": "INCIDENT_ESCALATED", "recipient": {"userId": "%s", "name": "Bob Reporter",
                "email": "bob@example.com"}, "subject": "[SEV1] Escalated: Checkout is slow", "body": "Raised to SEV1"
                """.formatted(BOB.userId()));

        service.accept(command, NotificationPriority.URGENT);

        Notification stored = stored().get(0);
        assertThat(stored.getRecipientEmail()).isEqualTo("bob@example.com");
        assertThat(stored.getSubject()).isEqualTo("[SEV1] Escalated: Checkout is slow");
        assertThat(stored.getBody()).isEqualTo("Raised to SEV1");
        verifyNoInteractions(organization);
    }

    @Test
    void repeatedMessageStoresNothing() throws Exception {
        NotificationMessage message = message("incident.created", BOB.userId(), "");
        when(processed.existsById(message.eventId())).thenReturn(true);

        assertThat(service.accept(message, NotificationPriority.URGENT)).isEmpty();

        verify(repository, never()).saveAll(any());
        verify(processed, never()).save(any());
    }

    private Notification pending(String email) {
        return new Notification(new EmailMessage(incidentId, new Recipient(UUID.randomUUID(), "X", email),
                NotificationReason.INCIDENT_CREATED, "subject", "body"), NotificationPriority.NORMAL, NOW);
    }

    @Test
    void deliverDueSendsAndMarksSent() {
        Notification notification = pending("dan@example.com");
        when(repository.lockDue(NOW, 50)).thenReturn(List.of(notification));

        assertThat(service.deliverDue()).isEqualTo(1);

        verify(emailSender).send("dan@example.com", "subject", "body");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void failedSendIsRetriedAndOthersStillGoOut() {
        Notification failing = pending("bad@example.com");
        Notification fine = pending("dan@example.com");
        when(repository.lockDue(NOW, 50)).thenReturn(List.of(failing, fine));
        doThrow(new MailSendException("SMTP down")).when(emailSender)
                .send(eq("bad@example.com"), anyString(), anyString());

        service.deliverDue();

        assertThat(failing.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(failing.getLastError()).isEqualTo("SMTP down");
        assertThat(fine.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void deliverNowSendsTheGivenOnesThatAreStillDue() {
        Notification notification = pending("dan@example.com");
        when(repository.lockDueByIds(List.of(notification.getId()), NOW)).thenReturn(List.of(notification));

        assertThat(service.deliverNow(List.of(notification.getId()))).isEqualTo(1);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(service.deliverNow(List.of())).isZero();
    }
}
