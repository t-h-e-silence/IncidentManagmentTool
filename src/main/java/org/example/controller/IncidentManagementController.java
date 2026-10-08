package org.example.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.example.audit.model.AuditAction;
import org.example.audit.model.AuditEntityType;
import org.example.audit.model.AuditEntryView;
import org.example.audit.model.AuditRecord;
import org.example.audit.service.AuditService;
import org.example.common.exception.BusinessRuleException;
import org.example.common.exception.NotFoundException;
import org.example.common.model.Actor;
import org.example.common.model.Recipient;
import org.example.common.model.Severity;
import org.example.controller.model.AddCommentCommand;
import org.example.controller.model.UpdateIncidentCommand;
import org.example.escalations.model.EscalationRecipients;
import org.example.escalations.model.EscalationRecord;
import org.example.escalations.service.EscalationService;
import org.example.incidents.model.CommentView;
import org.example.incidents.model.IncidentChange;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentSummary;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;
import org.example.incidents.service.IncidentService;
import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.NotificationReason;
import org.example.notifications.service.NotificationService;
import org.example.organization.model.CategoryRouting;
import org.example.organization.model.TeamView;
import org.example.organization.service.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single entry point of the application, over HTTP: every endpoint is one user action.
 *
 * <ul>
 *   <li>{@code GET /teams} — list teams</li>
 *   <li>{@code GET /incidents}, {@code GET /teams/{teamId}/incidents}, {@code GET /users/{user}/incidents} — list
 *       incidents: all, by owning team, by reporter</li>
 *   <li>{@code POST /incidents} — create an incident</li>
 *   <li>{@code PATCH /incidents/{id}} — update name, description, severity (escalation / de-escalation) and status</li>
 *   <li>{@code GET /incidents/{id}}, {@code POST /incidents/{id}/comments}, {@code GET /incidents/{id}/history} —
 *       view, comment, audit history</li>
 * </ul>
 *
 * <p>The caller is the {@value ActingUser#HEADER} header (user id or username). Each method checks that the caller
 * is an active user, then calls the module services in order, in one transaction: the incident change, its audit
 * entries (sharing one correlation id) and its notifications are saved together or not at all. Emails are sent after commit by the
 * notifications delivery job.
 *
 * <p>Errors are exceptions from {@code org.example.common.exception}, mapped to status codes by
 * {@link HttpErrorHandler}: unknown/inactive caller 401, not allowed 403, missing 404, rule violated 409,
 * invalid input 400.
 */
@RestController
@Transactional
public class IncidentManagementController {

    private static final Logger log = LoggerFactory.getLogger(IncidentManagementController.class);

    private final OrganizationService organization;
    private final IncidentService incidents;
    private final AuditService audit;
    private final NotificationService notifications;
    private final EscalationService escalations;

    public IncidentManagementController(OrganizationService organization, IncidentService incidents,
                                        AuditService audit, NotificationService notifications,
                                        EscalationService escalations) {
        this.organization = organization;
        this.incidents = incidents;
        this.audit = audit;
        this.notifications = notifications;
        this.escalations = escalations;
    }

    // ---------------------------------------------------------------- 1. teams

    /**
     * All teams, archived ones included, by name, each with its active members.
     */
    @GetMapping("/teams")
    @Transactional(readOnly = true)
    public List<TeamView> listTeams(@ActingUser UUID actorId) {
        organization.getActiveActor(actorId);
        return organization.listTeams();
    }

    // ---------------------------------------------------------------- 2. list incidents

    /**
     * Every incident, in any status, newest first.
     */
    @GetMapping("/incidents")
    @Transactional(readOnly = true)
    public List<IncidentSummary> listAllIncidents(@ActingUser UUID actorId) {
        organization.getActiveActor(actorId);
        return incidents.listAll();
    }

    /**
     * Incidents a team owns, in any status, newest first.
     */
    @GetMapping("/teams/{teamId}/incidents")
    @Transactional(readOnly = true)
    public List<IncidentSummary> listTeamIncidents(@ActingUser UUID actorId, @PathVariable UUID teamId) {
        organization.getActiveActor(actorId);
        organization.getTeam(teamId);
        return incidents.listByTeam(teamId);
    }

    /**
     * Incidents a user reported, newest first.
     *
     * @param user user id or username
     */
    @GetMapping("/users/{user}/incidents")
    @Transactional(readOnly = true)
    public List<IncidentSummary> listUserIncidents(@ActingUser UUID actorId, @PathVariable String user) {
        organization.getActiveActor(actorId);
        UUID userId = organization.findUserId(user)
                .orElseThrow(() -> new NotFoundException("User " + user + " not found"));
        return incidents.listReportedBy(userId);
    }

    // ---------------------------------------------------------------- 3. create

    /**
     * Reports an incident; the category decides the owning team, which is emailed.
     */
    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentView createIncident(@ActingUser UUID actorId, @RequestBody ReportIncidentCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        String correlationId = newCorrelationId();
        CategoryRouting routing = organization.getRouting(command.categoryId());

        IncidentView incident = incidents.create(actor, command, routing.categoryName(), routing.teamId());
        audit(actor, AuditAction.INCIDENT_CREATED, incident.id(), correlationId,
                "team", incident.teamId().toString(), "severity", incident.severity().name());
        notifications.notifyIncident(notice(incident, NotificationReason.INCIDENT_CREATED, null),
                teamRecipients(incident.teamId()));
        log.info("{} reported incident {} ({}, {}) for team {}", actor.name(), incident.id(), incident.severity(),
                routing.categoryName(), incident.teamId());
        return incident;
    }

    // ---------------------------------------------------------------- 4. update

    /**
     * Updates an incident; all given changes succeed together or none does. In order:
     * <ol>
     *   <li><b>title / description</b> — by the owning team or the reporter; no emails.</li>
     *   <li><b>severity</b> — by the owning team, with a reason. Higher = <b>escalation</b>, optionally handing the
     *       incident over to {@code targetTeamId}; lower = <b>de-escalation</b>, same team. Both are recorded by the
     *       escalations module, which emails the owning team, the previous team (if handed over) and the
     *       reporter.</li>
     *   <li><b>status</b> — by the owning team:
     *       {@code OPEN -> IN_PROGRESS -> IN_REVIEW -> RESOLVED -> CLOSED}, {@code IN_REVIEW -> IN_PROGRESS},
     *       {@code RESOLVED -> IN_PROGRESS} (reopen), {@code OPEN | IN_PROGRESS | IN_REVIEW -> CANCELLED}.
     *       A reason is needed to resolve, cancel or reopen. Emails: acknowledged (to {@code IN_PROGRESS} from
     *       {@code OPEN}) → reporter; resolved, reopened, cancelled → team and reporter.</li>
     * </ol>
     */
    @PatchMapping("/incidents/{incidentId}")
    public IncidentView updateIncident(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                       @RequestBody UpdateIncidentCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        String correlationId = newCorrelationId();
        IncidentView incident = incidents.get(incidentId);
        if (command.changesDetails()) {
            incident = updateDetails(actor, incidentId, command, correlationId);
        }
        if (command.severity() != null) {
            incident = changeSeverity(actor, incident, command, correlationId);
        }
        if (command.status() != null) {
            incident = changeStatus(actor, incidentId, command.status(), command.reason(), correlationId);
        }
        return incident;
    }

    // ---------------------------------------------------------------- view, comment, history

    @GetMapping("/incidents/{incidentId}")
    @Transactional(readOnly = true)
    public IncidentView getIncident(@ActingUser UUID actorId, @PathVariable UUID incidentId) {
        organization.getActiveActor(actorId);
        return incidents.get(incidentId);
    }

    /**
     * By a member of the owning team or the reporter, while the incident is active.
     */
    @PostMapping("/incidents/{incidentId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView addComment(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                  @RequestBody AddCommentCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        CommentView comment = incidents.addComment(actor, incidentId, command.text());
        audit.record(new AuditRecord(actor.id(), AuditAction.COMMENT_ADDED, AuditEntityType.COMMENT, comment.id(),
                incidentId, Map.of(), newCorrelationId()));
        log.info("{} commented on incident {}", actor.name(), incidentId);
        return comment;
    }

    /**
     * The audit history of an incident, oldest first: who did what, with details (from/to, reasons).
     */
    @GetMapping("/incidents/{incidentId}/history")
    @Transactional(readOnly = true)
    public List<AuditEntryView> getIncidentHistory(@ActingUser UUID actorId, @PathVariable UUID incidentId) {
        organization.getActiveActor(actorId);
        incidents.get(incidentId);
        return audit.getIncidentTimeline(incidentId);
    }

    // ---------------------------------------------------------------- update steps

    private IncidentView updateDetails(Actor actor, UUID incidentId, UpdateIncidentCommand command,
                                       String correlationId) {
        IncidentChange change = incidents.updateDetails(actor, incidentId, command.title(), command.description());
        IncidentView before = change.before();
        IncidentView after = change.after();
        audit(actor, AuditAction.DETAILS_UPDATED, incidentId, correlationId,
                "fromTitle", before.title(), "toTitle", after.title(),
                "descriptionChanged", String.valueOf(!before.description().equals(after.description())));
        log.info("{} updated title/description of incident {}", actor.name(), incidentId);
        return after;
    }

    /**
     * Escalation (severity up, optional hand-over) or de-escalation (severity down, same team).
     */
    private IncidentView changeSeverity(Actor actor, IncidentView current, UpdateIncidentCommand command,
                                        String correlationId) {
        Severity severity = command.severity();
        UUID targetTeamId = command.targetTeamId();
        if (severity == current.severity()) {
            throw new BusinessRuleException("Severity is already " + severity);
        }
        boolean raise = severity.isHigherThan(current.severity());
        IncidentChange change;
        if (raise) {
            if (targetTeamId != null) {
                organization.requireActiveTeam(targetTeamId);
            }
            change = incidents.escalate(actor, current.id(), severity, targetTeamId);
        } else {
            if (targetTeamId != null) {
                throw new BusinessRuleException("Only an escalation (severity raise) can hand the incident over to "
                        + "another team");
            }
            change = incidents.deEscalate(actor, current.id(), severity);
        }
        return recordEscalation(actor, raise ? AuditAction.ESCALATED : AuditAction.DE_ESCALATED, change,
                command.reason(), correlationId);
    }

    /**
     * Audits an escalation or de-escalation; the escalations module records it and emails the owning team, the
     * previous team (if handed over) and the reporter.
     */
    private IncidentView recordEscalation(Actor actor, AuditAction action, IncidentChange change, String reason,
                                          String correlationId) {
        IncidentView before = change.before();
        IncidentView after = change.after();
        UUID incidentId = after.id();
        log.info("{} {} incident {}: {} -> {}, team {} -> {}", actor.name(),
                action == AuditAction.ESCALATED ? "escalated" : "de-escalated", incidentId, before.severity(),
                after.severity(), before.teamId(), after.teamId());
        audit(actor, action, incidentId, correlationId,
                "fromSeverity", before.severity().name(), "toSeverity", after.severity().name(),
                "fromTeam", before.teamId().toString(), "toTeam", after.teamId().toString(),
                "reason", reason);

        boolean handedOver = !before.teamId().equals(after.teamId());
        String fromTeamName = organization.getTeam(before.teamId()).name();
        String toTeamName = handedOver ? organization.getTeam(after.teamId()).name() : fromTeamName;
        escalations.recordAndNotify(
                new EscalationRecord(incidentId, after.title(), actor.id(), actor.name(), reason,
                        before.severity(), after.severity(), before.teamId(), fromTeamName, after.teamId(),
                        toTeamName),
                new EscalationRecipients(
                        teamRecipients(after.teamId()),
                        handedOver ? organization.getActiveMembers(before.teamId()) : List.of(),
                        reporterRecipient(after, actor).stream().findFirst()));
        return after;
    }

    /**
     * Moves the status, audits it and emails whoever should know about this step.
     */
    private IncidentView changeStatus(Actor actor, UUID incidentId, IncidentStatus status, String note,
                                      String correlationId) {
        IncidentChange change = incidents.changeStatus(actor, incidentId, status, note);
        IncidentStatus from = change.before().status();
        IncidentView incident = change.after();
        IncidentStatus to = incident.status();
        log.info("{} moved incident {} from {} to {}", actor.name(), incident.id(), from, to);
        if (note == null || note.isBlank()) {
            audit(actor, AuditAction.STATUS_CHANGED, incident.id(), correlationId,
                    "from", from.name(), "to", to.name());
        } else {
            audit(actor, AuditAction.STATUS_CHANGED, incident.id(), correlationId,
                    "from", from.name(), "to", to.name(), "note", note.strip());
        }

        if (to == IncidentStatus.IN_PROGRESS && from == IncidentStatus.OPEN) {
            notifications.notifyIncident(notice(incident, NotificationReason.INCIDENT_ACKNOWLEDGED, null),
                    reporterRecipient(incident, actor));
        } else if (to == IncidentStatus.IN_PROGRESS && from == IncidentStatus.RESOLVED) {
            notifyTeamAndReporter(actor, incident, NotificationReason.INCIDENT_REOPENED, note);
        } else if (to == IncidentStatus.RESOLVED) {
            notifyTeamAndReporter(actor, incident, NotificationReason.INCIDENT_RESOLVED, note);
        } else if (to == IncidentStatus.CANCELLED) {
            notifyTeamAndReporter(actor, incident, NotificationReason.INCIDENT_CANCELLED, note);
        }
        return incident;
    }

    // ---------------------------------------------------------------- helpers

    private void notifyTeamAndReporter(Actor actor, IncidentView incident, NotificationReason reason, String note) {
        notifications.notifyIncident(notice(incident, reason, note),
                union(teamRecipients(incident.teamId()), reporterRecipient(incident, actor)));
    }

    /**
     * @param details alternating keys and values
     */
    private void audit(Actor actor, AuditAction action, UUID incidentId, String correlationId, String... details) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < details.length; i += 2) {
            map.put(details[i], details[i + 1]);
        }
        audit.record(new AuditRecord(actor.id(), action, AuditEntityType.INCIDENT, incidentId, incidentId, map,
                correlationId));
    }

    private IncidentNotice notice(IncidentView incident, NotificationReason reason, String note) {
        String teamName = organization.getTeam(incident.teamId()).name();
        return new IncidentNotice(incident.id(), incident.title(), incident.severity(), teamName, reason, note);
    }

    /**
     * Active members of the team; the active admins if it has none, so nobody misses the incident.
     */
    private List<Recipient> teamRecipients(UUID teamId) {
        List<Recipient> members = organization.getActiveMembers(teamId);
        if (!members.isEmpty()) {
            return members;
        }
        log.warn("Team {} has no active members, notifying the admins instead", teamId);
        return organization.getActiveAdmins();
    }

    /**
     * The reporter, unless they are the one acting or are no longer active.
     */
    private List<Recipient> reporterRecipient(IncidentView incident, Actor actor) {
        if (incident.reporterId().equals(actor.id())) {
            return List.of();
        }
        return organization.findRecipient(incident.reporterId()).map(List::of).orElse(List.of());
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

    private static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
