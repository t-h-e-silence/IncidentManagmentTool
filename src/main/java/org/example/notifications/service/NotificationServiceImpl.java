package org.example.notifications.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.example.common.exception.NotFoundException;
import org.example.common.model.Recipient;
import org.example.notifications.model.EmailMessage;
import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.Notification;
import org.example.notifications.model.NotificationStatus;
import org.example.notifications.model.NotificationView;
import org.example.notifications.model.RetryPolicy;
import org.example.notifications.repository.NotificationRepository;
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
    @Transactional(readOnly = true)
    public List<NotificationView> getForIncident(UUID incidentId) {
        return notifications.findByIncidentIdOrderByCreatedAt(incidentId).stream().map(Notification::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationView> getDeadLettered() {
        return notifications.findByStatusOrderByCreatedAt(NotificationStatus.DEAD_LETTERED).stream()
                .map(Notification::toView).toList();
    }

    @Override
    public NotificationView replay(UUID notificationId) {
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification " + notificationId + " not found"));
        notification.replay(clock.instant());
        return notification.toView();
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
