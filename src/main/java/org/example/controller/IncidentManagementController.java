package org.example.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.example.audit.model.AuditAction;
import org.example.audit.model.AuditEntityType;
import org.example.audit.model.AuditEntryView;
import org.example.audit.model.AuditRecord;
import org.example.audit.service.AuditService;
import org.example.common.exception.ForbiddenException;
import org.example.common.model.Actor;
import org.example.common.model.Recipient;
import org.example.common.model.Severity;
import org.example.controller.model.EscalateCommand;
import org.example.escalations.model.EscalationRecipients;
import org.example.escalations.model.EscalationRecord;
import org.example.escalations.model.EscalationView;
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
import org.example.notifications.model.NotificationView;
import org.example.notifications.service.NotificationService;
import org.example.organization.model.CategoryRouting;
import org.example.organization.model.CategoryView;
import org.example.organization.model.TeamView;
import org.example.organization.service.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single entry point for users: every user action is one method here. There is no HTTP API.
 *
 * <p>Each method checks the actor ({@code actorId} must be an active user), then calls the module services in
 * order. Each method is one transaction: the incident change, its audit entry and its notifications are saved
 * together or not at all. Emails are sent after commit by the notifications delivery job.
 *
 * <p>Errors are exceptions from {@code org.example.common.exception}: unknown/inactive actor →
 * {@code UnauthenticatedException}, not allowed → {@code ForbiddenException}, missing → {@code NotFoundException},
 * rule violated → {@code BusinessRuleException}.
 */
@Component
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

    // ---------------------------------------------------------------- organization

    /**
     * The id of a user given by id or username (e.g. {@code bob}), so callers can name users briefly; empty if
     * unknown. Does not check that the user may act.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findUserId(String userIdOrUsername) {
        return organization.findUserId(userIdOrUsername);
    }

    /**
     * Categories the actor can report an incident in.
     */
    @Transactional(readOnly = true)
    public List<CategoryView> listCategories(UUID actorId) {
        organization.getActiveActor(actorId);
        return organization.listActiveCategories();
    }

    /**
     * All teams, archived ones included, by name, each with its active members.
     */
    @Transactional(readOnly = true)
    public List<TeamView> listTeams(UUID actorId) {
        organization.getActiveActor(actorId);
        return organization.listTeams();
    }

    /**
     * A team and its active members.
     */
    @Transactional(readOnly = true)
    public TeamView getTeam(UUID actorId, UUID teamId) {
        organization.getActiveActor(actorId);
        return organization.getTeam(teamId);
    }

    // ---------------------------------------------------------------- reporting

    /**
     * Reports an incident; the category decides the owning team, which is emailed.
     */
    public IncidentView reportIncident(UUID actorId, ReportIncidentCommand command) {
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

    @Transactional(readOnly = true)
    public IncidentView getIncident(UUID actorId, UUID incidentId) {
        organization.getActiveActor(actorId);
        return incidents.get(incidentId);
    }

    /**
     * Incidents the actor reported, newest first.
     */
    @Transactional(readOnly = true)
    public List<IncidentSummary> listMyReportedIncidents(UUID actorId) {
        organization.getActiveActor(actorId);
        return incidents.listReportedBy(actorId);
    }

    /**
     * Every incident, in any status, newest first.
     */
    @Transactional(readOnly = true)
    public List<IncidentSummary> listAllIncidents(UUID actorId) {
        organization.getActiveActor(actorId);
        return incidents.listAll();
    }

    /**
     * Incidents a user reported, newest first.
     */
    @Transactional(readOnly = true)
    public List<IncidentSummary> listIncidentsReportedBy(UUID actorId, UUID userId) {
        organization.getActiveActor(actorId);
        return incidents.listReportedBy(userId);
    }

    /**
     * New title and/or description; null keeps the current value. No emails.
     * By a member of the owning team or the reporter, while the incident is active.
     */
    public IncidentView updateIncidentDetails(UUID actorId, UUID incidentId, String title, String description) {
        Actor actor = organization.getActiveActor(actorId);
        IncidentChange change = incidents.updateDetails(actor, incidentId, title, description);
        IncidentView before = change.before();
        IncidentView after = change.after();
        audit(actor, AuditAction.DETAILS_UPDATED, incidentId, newCorrelationId(),
                "fromTitle", before.title(), "toTitle", after.title(),
                "descriptionChanged", String.valueOf(!before.description().equals(after.description())));
        log.info("{} updated title/description of incident {}", actor.name(), incidentId);
        return after;
    }

    /**
     * By a member of the owning team or the reporter.
     */
    public CommentView addComment(UUID actorId, UUID incidentId, String text) {
        Actor actor = organization.getActiveActor(actorId);
        CommentView comment = incidents.addComment(actor, incidentId, text);
        audit.record(new AuditRecord(actor.id(), AuditAction.COMMENT_ADDED, AuditEntityType.COMMENT, comment.id(),
                incidentId, Map.of(), newCorrelationId()));
        log.info("{} commented on incident {}", actor.name(), incidentId);
        return comment;
    }

    // ---------------------------------------------------------------- working an incident

    /**
     * All incidents a team owns, in any status, newest first.
     */
    @Transactional(readOnly = true)
    public List<IncidentSummary> listTeamIncidents(UUID actorId, UUID teamId) {
        organization.getActiveActor(actorId);
        organization.getTeam(teamId);
        return incidents.listByTeam(teamId);
    }

    /**
     * Active ({@code OPEN}, {@code IN_PROGRESS}, {@code IN_REVIEW}) incidents of a team, most severe first, then
     * oldest first.
     */
    @Transactional(readOnly = true)
    public List<IncidentSummary> getTeamQueue(UUID actorId, UUID teamId) {
        organization.getActiveActor(actorId);
        organization.getTeam(teamId);
        return incidents.listTeamQueue(teamId);
    }

    /**
     * {@code OPEN -> IN_PROGRESS}; the reporter is emailed.
     */
    public IncidentView acknowledgeIncident(UUID actorId, UUID incidentId) {
        Actor actor = organization.getActiveActor(actorId);
        return afterStatusChange(actor, incidents.acknowledge(actor, incidentId), null);
    }

    /**
     * {@code IN_REVIEW -> RESOLVED} with a note; the team and the reporter are emailed.
     */
    public IncidentView resolveIncident(UUID actorId, UUID incidentId, String note) {
        Actor actor = organization.getActiveActor(actorId);
        return afterStatusChange(actor, incidents.resolve(actor, incidentId, note), note);
    }

    /**
     * Any step of the lifecycle, by a member of the owning team:
     * <pre>
     * OPEN -> IN_PROGRESS -> IN_REVIEW -> RESOLVED -> CLOSED
     * IN_REVIEW -> IN_PROGRESS (back to work), RESOLVED -> IN_PROGRESS (reopen)
     * OPEN | IN_PROGRESS | IN_REVIEW -> CANCELLED
     * </pre>
     * {@code note} is required to resolve, cancel or reopen. Emails: acknowledged → reporter; resolved, reopened,
     * cancelled → team and reporter; the other steps send none.
     */
    public IncidentView changeIncidentStatus(UUID actorId, UUID incidentId, IncidentStatus status, String note) {
        Actor actor = organization.getActiveActor(actorId);
        return afterStatusChange(actor, incidents.changeStatus(actor, incidentId, status, note), note);
    }

    /**
     * Raise or lower severity, e.g. to correct the reporter's estimate. No emails; an escalation or de-escalation
     * (with a reason, optionally to another team, and emails) is {@link #escalateIncident} / {@link #deEscalateIncident}.
     */
    public IncidentView changeSeverity(UUID actorId, UUID incidentId, Severity severity) {
        Actor actor = organization.getActiveActor(actorId);
        IncidentChange change = incidents.changeSeverity(actor, incidentId, severity);
        audit(actor, AuditAction.SEVERITY_CHANGED, incidentId, newCorrelationId(),
                "from", change.before().severity().name(), "to", change.after().severity().name());
        log.info("{} changed severity of incident {} from {} to {}", actor.name(), incidentId,
                change.before().severity(), change.after().severity());
        return change.after();
    }

    /**
     * Escalates: severity goes up, and the incident may be handed over to another active team. The owning team,
     * the previous team (if handed over) and the reporter each get their own email, built by the escalations module.
     * By a member of the owning team.
     */
    public IncidentView escalateIncident(UUID actorId, UUID incidentId, EscalateCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        if (command.targetTeamId() != null) {
            organization.requireActiveTeam(command.targetTeamId());
        }
        IncidentChange change = incidents.escalate(actor, incidentId, command.severity(), command.targetTeamId());
        return recordEscalation(actor, AuditAction.ESCALATED, change, command);
    }

    /**
     * De-escalates: severity goes down, and the incident may be handed over to another active team. Recorded and
     * emailed like an escalation. By a member of the owning team.
     */
    public IncidentView deEscalateIncident(UUID actorId, UUID incidentId, EscalateCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        if (command.targetTeamId() != null) {
            organization.requireActiveTeam(command.targetTeamId());
        }
        IncidentChange change = incidents.deEscalate(actor, incidentId, command.severity(), command.targetTeamId());
        return recordEscalation(actor, AuditAction.DE_ESCALATED, change, command);
    }

    /**
     * Hands the incident over to another active team without changing severity; the new team and the
     * reporter are emailed. By a member of the owning team or an admin.
     */
    public IncidentView reassignIncident(UUID actorId, UUID incidentId, UUID targetTeamId, String reason) {
        Actor actor = organization.getActiveActor(actorId);
        organization.requireActiveTeam(targetTeamId);
        IncidentChange change = incidents.reassign(actor, incidentId, targetTeamId);
        audit(actor, AuditAction.REASSIGNED, incidentId, newCorrelationId(),
                "fromTeam", change.before().teamId().toString(), "toTeam", targetTeamId.toString(),
                "reason", Objects.requireNonNullElse(reason, ""));
        IncidentView incident = change.after();
        notifications.notifyIncident(notice(incident, NotificationReason.INCIDENT_REASSIGNED, reason),
                union(teamRecipients(targetTeamId), reporterRecipient(incident, actor)));
        log.info("{} reassigned incident {} from team {} to {}", actor.name(), incidentId,
                change.before().teamId(), targetTeamId);
        return incident;
    }

    // ---------------------------------------------------------------- history

    /**
     * Everything that happened to an incident, oldest first.
     */
    @Transactional(readOnly = true)
    public List<AuditEntryView> getIncidentTimeline(UUID actorId, UUID incidentId) {
        organization.getActiveActor(actorId);
        incidents.get(incidentId);
        return audit.getIncidentTimeline(incidentId);
    }

    /**
     * Emails sent (or pending) about an incident.
     */
    @Transactional(readOnly = true)
    public List<NotificationView> getIncidentNotifications(UUID actorId, UUID incidentId) {
        organization.getActiveActor(actorId);
        incidents.get(incidentId);
        return notifications.getForIncident(incidentId);
    }

    /**
     * Escalations of an incident, oldest first.
     */
    @Transactional(readOnly = true)
    public List<EscalationView> getIncidentEscalations(UUID actorId, UUID incidentId) {
        organization.getActiveActor(actorId);
        incidents.get(incidentId);
        return escalations.getEscalations(incidentId);
    }

    // ---------------------------------------------------------------- admin

    /**
     * Emails that failed every attempt. Admins only.
     */
    @Transactional(readOnly = true)
    public List<NotificationView> listDeadLetteredNotifications(UUID adminId) {
        requireAdmin(adminId);
        return notifications.getDeadLettered();
    }

    /**
     * Sends a dead-lettered email again. Admins only.
     */
    public NotificationView replayNotification(UUID adminId, UUID notificationId) {
        Actor admin = requireAdmin(adminId);
        NotificationView notification = notifications.replay(notificationId);
        audit.record(new AuditRecord(admin.id(), AuditAction.NOTIFICATION_REPLAYED, AuditEntityType.NOTIFICATION,
                notification.id(), notification.incidentId(), Map.of("recipient", notification.recipientId().toString()),
                newCorrelationId()));
        log.info("{} replayed notification {}", admin.name(), notificationId);
        return notification;
    }

    /**
     * Everything a user did, oldest first. Admins only.
     */
    @Transactional(readOnly = true)
    public List<AuditEntryView> getUserActivity(UUID adminId, UUID userId) {
        requireAdmin(adminId);
        return audit.getActionsByUser(userId);
    }

    // ---------------------------------------------------------------- helpers

    private Actor requireAdmin(UUID actorId) {
        Actor actor = organization.getActiveActor(actorId);
        if (!actor.isAdmin()) {
            throw new ForbiddenException("Only admins may do this");
        }
        return actor;
    }

    /**
     * Audits an escalation or de-escalation; the escalations module records it and emails the owning team, the
     * previous team (if handed over) and the reporter.
     */
    private IncidentView recordEscalation(Actor actor, AuditAction action, IncidentChange change,
                                          EscalateCommand command) {
        IncidentView before = change.before();
        IncidentView after = change.after();
        UUID incidentId = after.id();
        log.info("{} {} incident {}: {} -> {}, team {} -> {}", actor.name(),
                action == AuditAction.ESCALATED ? "escalated" : "de-escalated", incidentId, before.severity(),
                after.severity(), before.teamId(), after.teamId());
        audit(actor, action, incidentId, newCorrelationId(),
                "fromSeverity", before.severity().name(), "toSeverity", after.severity().name(),
                "fromTeam", before.teamId().toString(), "toTeam", after.teamId().toString(),
                "reason", command.reason());

        boolean handedOver = !before.teamId().equals(after.teamId());
        String fromTeamName = organization.getTeam(before.teamId()).name();
        String toTeamName = handedOver ? organization.getTeam(after.teamId()).name() : fromTeamName;
        escalations.recordAndNotify(
                new EscalationRecord(incidentId, after.title(), actor.id(), actor.name(), command.reason(),
                        before.severity(), after.severity(), before.teamId(), fromTeamName, after.teamId(),
                        toTeamName),
                new EscalationRecipients(
                        teamRecipients(after.teamId()),
                        handedOver ? organization.getActiveMembers(before.teamId()) : List.of(),
                        reporterRecipient(after, actor).stream().findFirst()));
        return after;
    }

    /**
     * Audits a status change and emails whoever should know about this step.
     */
    private IncidentView afterStatusChange(Actor actor, IncidentChange change, String note) {
        IncidentStatus from = change.before().status();
        IncidentView incident = change.after();
        IncidentStatus to = incident.status();
        log.info("{} moved incident {} from {} to {}", actor.name(), incident.id(), from, to);
        if (note == null || note.isBlank()) {
            audit(actor, AuditAction.STATUS_CHANGED, incident.id(), newCorrelationId(),
                    "from", from.name(), "to", to.name());
        } else {
            audit(actor, AuditAction.STATUS_CHANGED, incident.id(), newCorrelationId(),
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
