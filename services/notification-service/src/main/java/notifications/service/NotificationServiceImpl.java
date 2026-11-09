package notifications.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import notifications.exception.NotFoundException;
import notifications.model.EmailMessage;
import notifications.model.IncidentNotice;
import notifications.model.IncidentStatus;
import notifications.model.Notification;
import notifications.model.NotificationMessage;
import notifications.model.NotificationPriority;
import notifications.model.NotificationReason;
import notifications.model.NotificationStatus;
import notifications.model.ProcessedEvent;
import notifications.model.Recipient;
import notifications.model.RetryPolicy;
import notifications.model.Severity;
import notifications.model.Team;
import notifications.repository.NotificationRepository;
import notifications.repository.ProcessedEventRepository;
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
    private final ProcessedEventRepository processed;
    private final OrganizationClient organization;
    private final EmailSender emailSender;
    private final Clock clock;
    private final int batchSize;
    private final RetryPolicy retryPolicy = RetryPolicy.DEFAULT;

    public NotificationServiceImpl(NotificationRepository notifications, ProcessedEventRepository processed,
                                   OrganizationClient organization, EmailSender emailSender, Clock clock,
                                   @Value("${notifications.delivery.batch-size}") int batchSize) {
        this.notifications = notifications;
        this.processed = processed;
        this.organization = organization;
        this.emailSender = emailSender;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public List<UUID> accept(NotificationMessage message, NotificationPriority priority) {
        if (processed.existsById(message.eventId())) {
            log.debug("Message {} ({}) was already consumed, skipping the repeat", message.eventId(), message.type());
            return List.of();
        }
        List<EmailMessage> emails = switch (message.type()) {
            case NotificationMessage.INCIDENT_CREATED -> incidentCreated(message);
            case NotificationMessage.INCIDENT_STATUS_CHANGED -> statusChanged(message);
            case NotificationMessage.EMAIL_REQUESTED -> List.of(requested(message));
            default -> {
                log.debug("Message {} has type {}, which sends no email", message.eventId(), message.type());
                yield List.of();
            }
        };
        Instant now = clock.instant();
        List<Notification> stored = notifications.saveAll(emails.stream()
                .map(email -> new Notification(email, priority, now))
                .toList());
        processed.save(new ProcessedEvent(message.eventId(), now));
        if (!stored.isEmpty()) {
            log.info("{} {} of incident {}: {} {} email(s) queued", message.type(), message.eventId(),
                    message.incidentId(), stored.size(), priority);
        }
        return stored.stream().map(Notification::getId).toList();
    }

    @Override
    public int deliverNow(List<UUID> notificationIds) {
        if (notificationIds.isEmpty()) {
            return 0;
        }
        List<Notification> due = notifications.lockDueByIds(notificationIds, clock.instant());
        due.forEach(this::deliver);
        return due.size();
    }

    @Override
    public int deliverDue() {
        List<Notification> due = notifications.lockDue(clock.instant(), batchSize);
        due.forEach(this::deliver);
        return due.size();
    }

    @Override
    public int deleteProcessedBefore(Instant before) {
        return processed.deleteProcessedBefore(before);
    }

    // ---------------------------------------------------------------- who gets which email

    private List<EmailMessage> incidentCreated(NotificationMessage message) {
        Team team = team(message);
        return render(notice(message, team, NotificationReason.INCIDENT_CREATED, null), teamRecipients(team));
    }

    /**
     * The same steps the monolith emailed about; review and closing send nothing.
     */
    private List<EmailMessage> statusChanged(NotificationMessage message) {
        IncidentStatus from = IncidentStatus.valueOf(message.text("fromStatus"));
        IncidentStatus to = IncidentStatus.valueOf(message.text("toStatus"));
        String note = message.text("note");
        if (to == IncidentStatus.IN_PROGRESS && from == IncidentStatus.OPEN) {
            Team team = team(message);
            return render(notice(message, team, NotificationReason.INCIDENT_ACKNOWLEDGED, null),
                    reporterRecipient(message));
        }
        NotificationReason reason;
        if (to == IncidentStatus.IN_PROGRESS && from == IncidentStatus.RESOLVED) {
            reason = NotificationReason.INCIDENT_REOPENED;
        } else if (to == IncidentStatus.RESOLVED) {
            reason = NotificationReason.INCIDENT_RESOLVED;
        } else if (to == IncidentStatus.CANCELLED) {
            reason = NotificationReason.INCIDENT_CANCELLED;
        } else {
            return List.of();
        }
        Team team = team(message);
        return render(notice(message, team, reason, note), union(teamRecipients(team), reporterRecipient(message)));
    }

    private static EmailMessage requested(NotificationMessage message) {
        Recipient recipient = new Recipient(UUID.fromString(message.nested("recipient", "userId")),
                message.nested("recipient", "name"), message.nested("recipient", "email"));
        return new EmailMessage(message.incidentId(), recipient, NotificationReason.valueOf(message.text("reason")),
                message.text("subject"), message.text("body"));
    }

    /**
     * The owning team after the change; an unknown team has no members, so its email goes to the admins.
     */
    private Team team(NotificationMessage message) {
        UUID teamId = UUID.fromString(message.nested("incident", "teamId"));
        try {
            return organization.getTeam(teamId);
        } catch (NotFoundException unknown) {
            log.warn("Team {} of incident {} is unknown", teamId, message.incidentId());
            return new Team(teamId, teamId.toString(), false, List.of());
        }
    }

    /**
     * Active members of the team; the active admins if it has none, so nobody misses the incident.
     */
    private List<Recipient> teamRecipients(Team team) {
        List<Recipient> members = team.recipients();
        if (!members.isEmpty()) {
            return members;
        }
        log.warn("Team {} has no active members, notifying the admins instead", team.id());
        return organization.getActiveAdmins();
    }

    /**
     * The reporter, unless they are the one acting or are no longer active.
     */
    private List<Recipient> reporterRecipient(NotificationMessage message) {
        UUID reporterId = UUID.fromString(message.nested("incident", "reporterId"));
        if (message.actor() != null && reporterId.equals(message.actor().id())) {
            return List.of();
        }
        return organization.findRecipient(reporterId).map(List::of).orElse(List.of());
    }

    /**
     * Both lists without duplicates (the reporter may also be a team member).
     */
    private static List<Recipient> union(List<Recipient> first, List<Recipient> second) {
        Map<UUID, Recipient> byUser = new LinkedHashMap<>();
        for (Recipient recipient : first) {
            byUser.putIfAbsent(recipient.userId(), recipient);
        }
        for (Recipient recipient : second) {
            byUser.putIfAbsent(recipient.userId(), recipient);
        }
        return new ArrayList<>(byUser.values());
    }

    private static IncidentNotice notice(NotificationMessage message, Team team, NotificationReason reason,
                                         String note) {
        return new IncidentNotice(message.incidentId(), message.nested("incident", "title"),
                Severity.valueOf(message.nested("incident", "severity")), team.name(), reason, note);
    }

    private static List<EmailMessage> render(IncidentNotice notice, List<Recipient> recipients) {
        String subject = NotificationTemplates.subject(notice);
        String body = NotificationTemplates.body(notice);
        return recipients.stream()
                .map(recipient -> new EmailMessage(notice.incidentId(), recipient, notice.reason(), subject, body))
                .toList();
    }

    // ---------------------------------------------------------------- delivery

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
