package org.example.incidents.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.example.common.exception.BusinessRuleException;
import org.example.common.exception.ForbiddenException;
import org.example.common.exception.NotFoundException;
import org.example.common.model.Actor;
import org.example.common.model.Severity;
import org.example.incidents.model.Comment;
import org.example.incidents.model.CommentView;
import org.example.incidents.model.Incident;
import org.example.incidents.model.IncidentChange;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentSummary;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;
import org.example.incidents.repository.IncidentRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IncidentServiceImpl implements IncidentService {

    private final IncidentRepository incidents;
    private final Clock clock;

    public IncidentServiceImpl(IncidentRepository incidents, Clock clock) {
        this.incidents = incidents;
        this.clock = clock;
    }

    @Override
    public IncidentView create(Actor reporter, ReportIncidentCommand command, String categoryName, UUID teamId) {
        Objects.requireNonNull(command.severity(), "severity");
        if (command.severity().isHigherThan(Severity.MAX_FOR_REPORTER)) {
            throw new BusinessRuleException("A reporter may set at most " + Severity.MAX_FOR_REPORTER);
        }
        Incident incident = new Incident(UUID.randomUUID(), command.title(), command.description(),
                command.categoryId(), categoryName, teamId, reporter.id(), command.severity(), clock.instant());
        return incidents.save(incident).toView();
    }

    @Override
    @Transactional(readOnly = true)
    public IncidentView get(UUID incidentId) {
        return load(incidentId).toView();
    }

    @Override
    @Transactional(readOnly = true)
    public List<IncidentSummary> listAll() {
        return summaries(incidents.findAllByOrderByCreatedAtDesc());
    }

    @Override
    @Transactional(readOnly = true)
    public List<IncidentSummary> listReportedBy(UUID userId) {
        return summaries(incidents.findByReporterIdOrderByCreatedAtDesc(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IncidentSummary> listByTeam(UUID teamId) {
        return summaries(incidents.findByTeamIdOrderByCreatedAtDesc(teamId));
    }

    @Override
    public CommentView addComment(Actor actor, UUID incidentId, String text) {
        Incident incident = load(incidentId);
        if (!actor.isMemberOf(incident.getTeamId()) && !actor.id().equals(incident.getReporterId())) {
            throw new ForbiddenException("Only the owning team or the reporter may comment on incident " + incidentId);
        }
        Comment comment = incident.addComment(actor.id(), text, clock.instant());
        save(incident);
        return comment.toView();
    }

    @Override
    public IncidentChange changeStatus(Actor actor, UUID incidentId, IncidentStatus status, String note) {
        return changeByTeamMember(actor, incidentId,
                incident -> incident.changeStatus(status, actor.id(), note, clock.instant()));
    }

    @Override
    public IncidentChange updateDetails(Actor actor, UUID incidentId, String title, String description) {
        Incident incident = load(incidentId);
        if (!actor.isMemberOf(incident.getTeamId()) && !actor.id().equals(incident.getReporterId())) {
            throw new ForbiddenException("Only the owning team or the reporter may edit incident " + incidentId);
        }
        return change(incident, i -> i.updateDetails(title, description, clock.instant()));
    }

    @Override
    public IncidentChange escalate(Actor actor, UUID incidentId, Severity newSeverity, UUID targetTeamId) {
        return changeByTeamMember(actor, incidentId,
                incident -> incident.escalate(newSeverity, targetTeamId, clock.instant()));
    }

    @Override
    public IncidentChange deEscalate(Actor actor, UUID incidentId, Severity newSeverity) {
        return changeByTeamMember(actor, incidentId, incident -> incident.deEscalate(newSeverity, clock.instant()));
    }

    private IncidentChange changeByTeamMember(Actor actor, UUID incidentId, Consumer<Incident> change) {
        Incident incident = load(incidentId);
        if (!actor.isMemberOf(incident.getTeamId())) {
            throw new ForbiddenException("Only members of the owning team may change incident " + incidentId);
        }
        return change(incident, change);
    }

    private IncidentChange change(Incident incident, Consumer<Incident> change) {
        IncidentView before = incident.toView();
        change.accept(incident);
        return new IncidentChange(before, save(incident).toView());
    }

    /**
     * Flushes at once, so a concurrent change of the same incident is reported here as a clear error.
     */
    private Incident save(Incident incident) {
        try {
            return incidents.saveAndFlush(incident);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessRuleException("Incident " + incident.getId() + " was changed concurrently, retry");
        }
    }

    private static List<IncidentSummary> summaries(List<Incident> list) {
        return list.stream().map(Incident::toSummary).toList();
    }

    private Incident load(UUID incidentId) {
        return incidents.findById(incidentId)
                .orElseThrow(() -> new NotFoundException("Incident " + incidentId + " not found"));
    }
}
