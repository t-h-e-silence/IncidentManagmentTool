package org.example.incidents.service;

import java.util.List;
import java.util.UUID;

import org.example.common.model.Actor;
import org.example.common.model.Severity;
import org.example.incidents.model.CommentView;
import org.example.incidents.model.IncidentChange;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentSummary;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;

/**
 * Incident lifecycle and comments. Changes check who may make them and throw
 * {@link org.example.common.exception.ForbiddenException},
 * {@link org.example.common.exception.NotFoundException} or
 * {@link org.example.common.exception.BusinessRuleException}.
 */
public interface IncidentService {

    /**
     * Reports a new {@code OPEN} incident owned by {@code teamId}.
     *
     * @throws org.example.common.exception.BusinessRuleException if the severity is above {@link Severity#MAX_FOR_REPORTER}
     */
    IncidentView create(Actor reporter, ReportIncidentCommand command, String categoryName, UUID teamId);

    IncidentView get(UUID incidentId);

    /**
     * All incidents, newest first.
     */
    List<IncidentSummary> listAll();

    /**
     * Incidents reported by the user, newest first.
     */
    List<IncidentSummary> listReportedBy(UUID userId);

    /**
     * All incidents the team owns, in any status, newest first.
     */
    List<IncidentSummary> listByTeam(UUID teamId);

    /**
     * Active ({@code OPEN}, {@code IN_PROGRESS}, {@code IN_REVIEW}) incidents of the team, most severe first,
     * then oldest first.
     */
    List<IncidentSummary> listTeamQueue(UUID teamId);

    /**
     * By a member of the owning team or the reporter.
     */
    CommentView addComment(Actor actor, UUID incidentId, String text);

    /**
     * {@code OPEN -> IN_PROGRESS}, by a member of the owning team.
     */
    IncidentChange acknowledge(Actor actor, UUID incidentId);

    /**
     * {@code IN_REVIEW -> RESOLVED} with a note, by a member of the owning team.
     */
    IncidentChange resolve(Actor actor, UUID incidentId, String note);

    /**
     * Any step of the lifecycle ({@link org.example.incidents.model.IncidentStatus}), by a member of the owning
     * team. A note is required to resolve, cancel or reopen.
     */
    IncidentChange changeStatus(Actor actor, UUID incidentId, IncidentStatus status, String note);

    /**
     * New title and/or description (null keeps the current value), by a member of the owning team or the reporter.
     */
    IncidentChange updateDetails(Actor actor, UUID incidentId, String title, String description);

    /**
     * Raise or lower severity, by a member of the owning team.
     */
    IncidentChange changeSeverity(Actor actor, UUID incidentId, Severity severity);

    /**
     * Escalation by a member of the owning team: severity must go up; {@code targetTeamId} (optional) hands the
     * incident over to another team. The caller checks that the target team is active.
     */
    IncidentChange escalate(Actor actor, UUID incidentId, Severity newSeverity, UUID targetTeamId);

    /**
     * De-escalation by a member of the owning team: severity must go down; {@code targetTeamId} (optional) hands
     * the incident over to another team. The caller checks that the target team is active.
     */
    IncidentChange deEscalate(Actor actor, UUID incidentId, Severity newSeverity, UUID targetTeamId);

    /**
     * Hand over to another team without changing severity, by a member of the owning team or an admin.
     * The caller checks that the target team is active.
     */
    IncidentChange reassign(Actor actor, UUID incidentId, UUID targetTeamId);
}
