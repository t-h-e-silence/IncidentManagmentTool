package modules.incidents.service;

import java.util.List;
import java.util.UUID;

import modules.common.exception.BusinessRuleException;
import modules.common.exception.ForbiddenException;
import modules.common.exception.NotFoundException;
import modules.common.model.Actor;
import modules.common.model.Severity;
import modules.incidents.model.CommentView;
import modules.incidents.model.IncidentChange;
import modules.incidents.model.IncidentStatus;
import modules.incidents.model.IncidentSummary;
import modules.incidents.model.IncidentView;
import modules.incidents.model.ReportIncidentCommand;

/**
 * Incident lifecycle and comments. Changes check who may make them and throw
 * {@link ForbiddenException},
 * {@link NotFoundException} or
 * {@link BusinessRuleException}.
 */
public interface IncidentService {

    /**
     * Reports a new {@code OPEN} incident owned by {@code teamId}.
     *
     * @throws BusinessRuleException if the severity is above {@link Severity#MAX_FOR_REPORTER}
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
     * By a member of the owning team or the reporter.
     */
    CommentView addComment(Actor actor, UUID incidentId, String text);

    /**
     * Any step of the lifecycle ({@link IncidentStatus}), by a member of the owning
     * team. A note is required to resolve, cancel or reopen.
     */
    IncidentChange changeStatus(Actor actor, UUID incidentId, IncidentStatus status, String note);

    /**
     * New title and/or description (null keeps the current value), by a member of the owning team or the reporter.
     */
    IncidentChange updateDetails(Actor actor, UUID incidentId, String title, String description);

    /**
     * Escalation by a member of the owning team: severity must go up; {@code targetTeamId} (optional) hands the
     * incident over to another team. The caller checks that the target team is active.
     */
    IncidentChange escalate(Actor actor, UUID incidentId, Severity newSeverity, UUID targetTeamId);

    /**
     * De-escalation by a member of the owning team: severity must go down; the team keeps the incident.
     */
    IncidentChange deEscalate(Actor actor, UUID incidentId, Severity newSeverity);
}
