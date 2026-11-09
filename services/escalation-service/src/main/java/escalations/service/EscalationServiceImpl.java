package escalations.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import escalations.exception.NotFoundException;
import escalations.model.EmailRequested;
import escalations.model.Escalation;
import escalations.model.EscalationRecipients;
import escalations.model.EscalationRecord;
import escalations.model.IncidentEvent;
import escalations.model.ProcessedEvent;
import escalations.model.Recipient;
import escalations.model.Severity;
import escalations.model.Team;
import escalations.repository.EscalationRepository;
import escalations.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EscalationServiceImpl implements EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationServiceImpl.class);

    private final EscalationRepository escalations;
    private final ProcessedEventRepository processed;
    private final OrganizationClient organization;
    private final OutboxService outbox;
    private final Clock clock;

    public EscalationServiceImpl(EscalationRepository escalations, ProcessedEventRepository processed,
                                 OrganizationClient organization, OutboxService outbox, Clock clock) {
        this.escalations = escalations;
        this.processed = processed;
        this.organization = organization;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void record(IncidentEvent event) {
        if (processed.existsById(event.eventId())) {
            log.debug("Event {} ({}) was already recorded, skipping the repeat", event.eventId(), event.type());
            return;
        }
        Instant now = clock.instant();
        if (IncidentEvent.ESCALATED.equals(event.type()) || IncidentEvent.DE_ESCALATED.equals(event.type())) {
            UUID fromTeamId = UUID.fromString(event.text("fromTeamId"));
            UUID toTeamId = UUID.fromString(event.text("toTeamId"));
            Team toTeam = team(toTeamId);
            Team fromTeam = fromTeamId.equals(toTeamId) ? toTeam : team(fromTeamId);
            EscalationRecord record = new EscalationRecord(event.incidentId(), event.incidentText("title"),
                    event.actor().id(), event.actor().name(), event.text("reason"),
                    Severity.valueOf(event.text("fromSeverity")), Severity.valueOf(event.text("toSeverity")),
                    fromTeamId, fromTeam.name(), toTeamId, toTeam.name());
            escalations.save(new Escalation(record, event.occurredAt()));
            EscalationRecipients recipients = new EscalationRecipients(
                    teamRecipients(toTeam),
                    record.handedOver() ? fromTeam.recipients() : List.of(),
                    reporterRecipient(event));
            List<EmailRequested> emails = emails(record, recipients, event.correlationId(), now);
            emails.forEach(outbox::publish);
            log.info("{} incident {}: {} -> {}, team {} -> {}; {} email(s) requested", record.direction(),
                    record.incidentId(), record.fromSeverity(), record.toSeverity(), record.fromTeamName(),
                    record.toTeamName(), emails.size());
        } else {
            log.debug("Event {} has type {}, which is not an escalation", event.eventId(), event.type());
        }
        processed.save(new ProcessedEvent(event.eventId(), now));
    }

    @Override
    public int deleteProcessedBefore(Instant before) {
        return processed.deleteProcessedBefore(before);
    }

    /**
     * Owning team first, then previous team, then reporter: someone in several groups gets the first matching
     * email only. The person who escalated is not emailed.
     */
    private static List<EmailRequested> emails(EscalationRecord record, EscalationRecipients recipients,
                                               String correlationId, Instant now) {
        Set<UUID> done = new HashSet<>();
        done.add(record.actorId());
        List<EmailRequested> emails = new ArrayList<>();
        for (Recipient recipient : recipients.owningTeam()) {
            add(emails, done, record, recipient, EscalationEmails.owningTeamSubject(record),
                    EscalationEmails.owningTeamBody(record), correlationId, now);
        }
        if (record.handedOver()) {
            for (Recipient recipient : recipients.previousTeam()) {
                add(emails, done, record, recipient, EscalationEmails.previousTeamSubject(record),
                        EscalationEmails.previousTeamBody(record), correlationId, now);
            }
        }
        recipients.reporter().ifPresent(reporter -> add(emails, done, record, reporter,
                EscalationEmails.reporterSubject(record), EscalationEmails.reporterBody(record), correlationId, now));
        return emails;
    }

    private static void add(List<EmailRequested> emails, Set<UUID> done, EscalationRecord record, Recipient recipient,
                            String subject, String body, String correlationId, Instant now) {
        if (done.add(recipient.userId())) {
            emails.add(EmailRequested.of(record, correlationId, recipient, subject, body, now));
        }
    }

    /**
     * A team with its active members; an unknown team has none, so its email goes to the admins.
     */
    private Team team(UUID teamId) {
        try {
            return organization.getTeam(teamId);
        } catch (NotFoundException unknown) {
            log.warn("Team {} is unknown", teamId);
            return new Team(teamId, teamId.toString(), false, List.of());
        }
    }

    /**
     * Active members of the owning team; the active admins if it has none, so nobody misses the escalation.
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
     * The reporter, unless they are the one escalating or are no longer active.
     */
    private Optional<Recipient> reporterRecipient(IncidentEvent event) {
        UUID reporterId = UUID.fromString(event.incidentText("reporterId"));
        if (reporterId.equals(event.actor().id())) {
            return Optional.empty();
        }
        return organization.findRecipient(reporterId);
    }
}
