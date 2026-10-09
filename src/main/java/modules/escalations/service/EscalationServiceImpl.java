package modules.escalations.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import modules.common.model.Recipient;
import modules.escalations.model.Escalation;
import modules.escalations.model.EscalationDirection;
import modules.escalations.model.EscalationRecipients;
import modules.escalations.model.EscalationRecord;
import modules.escalations.repository.EscalationRepository;
import modules.notifications.model.EmailMessage;
import modules.notifications.model.NotificationReason;
import modules.notifications.service.NotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EscalationServiceImpl implements EscalationService {

    private final EscalationRepository escalations;
    private final NotificationService notifications;
    private final Clock clock;

    public EscalationServiceImpl(EscalationRepository escalations, NotificationService notifications, Clock clock) {
        this.escalations = escalations;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Override
    public void recordAndNotify(EscalationRecord record, EscalationRecipients recipients) {
        escalations.save(new Escalation(record, clock.instant()));
        notifications.send(emails(record, recipients));
    }

    /**
     * Owning team first, then previous team, then reporter: someone in several groups gets the first matching
     * email only. The person who escalated is not emailed.
     */
    private static List<EmailMessage> emails(EscalationRecord record, EscalationRecipients recipients) {
        Set<UUID> done = new HashSet<>();
        done.add(record.actorId());
        List<EmailMessage> messages = new ArrayList<>();
        for (Recipient recipient : recipients.owningTeam()) {
            add(messages, done, record, recipient,
                    EscalationEmails.owningTeamSubject(record), EscalationEmails.owningTeamBody(record));
        }
        if (record.handedOver()) {
            for (Recipient recipient : recipients.previousTeam()) {
                add(messages, done, record, recipient,
                        EscalationEmails.previousTeamSubject(record), EscalationEmails.previousTeamBody(record));
            }
        }
        recipients.reporter().ifPresent(reporter -> add(messages, done, record, reporter,
                EscalationEmails.reporterSubject(record), EscalationEmails.reporterBody(record)));
        return messages;
    }

    private static void add(List<EmailMessage> messages, Set<UUID> done, EscalationRecord record,
                            Recipient recipient, String subject, String body) {
        if (done.add(recipient.userId())) {
            NotificationReason reason = record.direction() == EscalationDirection.UP
                    ? NotificationReason.INCIDENT_ESCALATED : NotificationReason.INCIDENT_DEESCALATED;
            messages.add(new EmailMessage(record.incidentId(), recipient, reason, subject, body));
        }
    }
}
