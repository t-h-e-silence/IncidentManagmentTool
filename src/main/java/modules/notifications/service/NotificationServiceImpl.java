package modules.notifications.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import modules.common.model.Recipient;
import modules.notifications.model.EmailMessage;
import modules.notifications.model.IncidentNotice;
import modules.notifications.model.Notification;
import modules.notifications.model.NotificationStatus;
import modules.notifications.model.RetryPolicy;
import modules.notifications.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository notifications;
    private final EmailSender emailSender;
    private final Clock clock;
    private final int batchSize;
    private final RetryPolicy retryPolicy = RetryPolicy.DEFAULT;

    public NotificationServiceImpl(NotificationRepository notifications, EmailSender emailSender, Clock clock,
                                   @Value("${notifications.delivery.batch-size}") int batchSize) {
        this.notifications = notifications;
        this.emailSender = emailSender;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public void notifyIncident(IncidentNotice notice, List<Recipient> recipients) {
        String subject = NotificationTemplates.subject(notice);
        String body = NotificationTemplates.body(notice);
        send(recipients.stream()
                .map(recipient -> new EmailMessage(notice.incidentId(), recipient, notice.reason(), subject, body))
                .toList());
    }

    @Override
    public void send(List<EmailMessage> messages) {
        Instant now = clock.instant();
        notifications.saveAll(messages.stream().map(message -> new Notification(message, now)).toList());
    }

    @Override
    public int deliverDue() {
        List<Notification> due = notifications.lockDue(clock.instant(), batchSize);
        for (Notification notification : due) {
            deliver(notification);
        }
        return due.size();
    }

    private void deliver(Notification notification) {
        try {
            emailSender.send(notification.getRecipientEmail(), notification.getSubject(), notification.getBody());
            notification.markSent(clock.instant());
        } catch (RuntimeException e) {
            notification.markFailed(e.getMessage(), clock.instant(), retryPolicy);
            if (notification.getStatus() == NotificationStatus.DEAD_LETTERED) {
                log.error("Notification {} for incident {} dead-lettered after {} attempts: {}",
                        notification.getId(), notification.getIncidentId(), notification.getAttempts(),
                        e.getMessage());
            } else {
                log.warn("Notification {} failed (attempt {}), will retry: {}",
                        notification.getId(), notification.getAttempts(), e.getMessage());
            }
        }
    }
}
