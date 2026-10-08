package org.example.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.TestData.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.example.common.model.Recipient;
import org.example.common.model.Severity;
import org.example.notifications.model.EmailMessage;
import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.Notification;
import org.example.notifications.model.NotificationReason;
import org.example.notifications.model.NotificationStatus;
import org.example.notifications.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    NotificationRepository repository;
    @Mock
    EmailSender emailSender;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(repository, emailSender, CLOCK, 50);
    }

    private Notification pending(String email) {
        return new Notification(new EmailMessage(UUID.randomUUID(),
                new Recipient(UUID.randomUUID(), "X", email),
                NotificationReason.INCIDENT_CREATED, "subject", "body"), NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyIncidentStoresOnePendingRowPerRecipient() {
        UUID incidentId = UUID.randomUUID();

        service.notifyIncident(new IncidentNotice(incidentId, "DB down", Severity.SEV2, "Database",
                NotificationReason.INCIDENT_CREATED, null), List.of(recipient(ALICE), recipient(DAN)));

        ArgumentCaptor<List<Notification>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2).allSatisfy(notification -> {
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.getIncidentId()).isEqualTo(incidentId);
            assertThat(notification.getSubject()).isEqualTo("[SEV2] New incident: DB down");
        });
        assertThat(saved.getValue()).extracting(Notification::getRecipientEmail)
                .containsExactly("alice@example.com", "dan@example.com");
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
}
