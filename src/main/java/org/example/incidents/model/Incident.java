package org.example.incidents.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.example.common.exception.BusinessRuleException;
import org.example.common.model.Severity;
import org.example.common.model.Text;

/**
 * An incident, owned by one team at a time. Enforces the lifecycle; who may call which method is checked by
 * {@link org.example.incidents.service.IncidentServiceImpl}.
 */
@Entity
@Table(name = "incident", schema = "incidents")
public class Incident {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int DESCRIPTION_MAX_LENGTH = 5000;
    public static final int NOTE_MAX_LENGTH = 2000;

    @Id
    private UUID id;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(nullable = false, length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /** Snapshot: the incident keeps the name it was reported under. */
    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolution_note", length = NOTE_MAX_LENGTH)
    private String resolutionNote;

    /** When it was closed or cancelled. */
    @Column(name = "closed_at")
    private Instant closedAt;

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL)
    @OrderBy("createdAt")
    private List<Comment> comments = new ArrayList<>();

    @Version
    private long version;

    protected Incident() {
        // for JPA
    }

    public Incident(UUID id, String title, String description, UUID categoryId, String categoryName, UUID teamId,
                    UUID reporterId, Severity severity, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Text.require(title, "title", TITLE_MAX_LENGTH);
        this.description = description == null || description.isBlank()
                ? "" : Text.require(description, "description", DESCRIPTION_MAX_LENGTH);
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId");
        this.categoryName = Objects.requireNonNull(categoryName, "categoryName");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.reporterId = Objects.requireNonNull(reporterId, "reporterId");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.status = IncidentStatus.OPEN;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * Moves along the lifecycle of {@link IncidentStatus}. A note is required to resolve, to cancel and to reopen
     * a resolved incident; it is kept as the resolution note when resolving.
     *
     * @param actorId who makes the change
     * @param note    optional otherwise
     */
    public void changeStatus(IncidentStatus target, UUID actorId, String note, Instant now) {
        Objects.requireNonNull(target, "status");
        if (!status.canMoveTo(target)) {
            throw new BusinessRuleException("Incident " + id + " cannot go from " + status + " to " + target);
        }
        IncidentStatus from = status;
        switch (target) {
            case IN_PROGRESS -> {
                if (from == IncidentStatus.OPEN) {
                    acknowledgedAt = now;
                } else if (from == IncidentStatus.RESOLVED) {
                    Text.require(note, "reopen reason", NOTE_MAX_LENGTH);
                    resolvedAt = null;
                    resolvedBy = null;
                    resolutionNote = null;
                }
            }
            case RESOLVED -> {
                resolutionNote = Text.require(note, "resolution note", NOTE_MAX_LENGTH);
                resolvedBy = Objects.requireNonNull(actorId, "actorId");
                resolvedAt = now;
            }
            case CANCELLED -> {
                Text.require(note, "cancel reason", NOTE_MAX_LENGTH);
                closedAt = now;
            }
            case CLOSED -> closedAt = now;
            case OPEN, IN_REVIEW -> {
                // nothing else changes
            }
        }
        status = target;
        updatedAt = now;
    }

    /**
     * Changes the title and/or the description; null keeps the current value, a blank description clears it.
     */
    public void updateDetails(String newTitle, String newDescription, Instant now) {
        requireActive();
        String checkedTitle = newTitle == null ? title : Text.require(newTitle, "title", TITLE_MAX_LENGTH);
        String checkedDescription = newDescription == null ? description
                : newDescription.isBlank() ? "" : Text.require(newDescription, "description", DESCRIPTION_MAX_LENGTH);
        if (checkedTitle.equals(title) && checkedDescription.equals(description)) {
            throw new BusinessRuleException("Nothing to change in incident " + id);
        }
        title = checkedTitle;
        description = checkedDescription;
        updatedAt = now;
    }

    /**
     * Hands the incident over to another team (part of an escalation). The new team has not acknowledged it yet,
     * so it is {@code OPEN} again.
     */
    private void reassign(UUID targetTeamId, Instant now) {
        Objects.requireNonNull(targetTeamId, "targetTeamId");
        requireActive();
        if (targetTeamId.equals(teamId)) {
            throw new BusinessRuleException("Incident is already owned by team " + teamId);
        }
        teamId = targetTeamId;
        status = IncidentStatus.OPEN;
        acknowledgedAt = null;
        updatedAt = now;
    }

    /**
     * Escalation: severity must go up; optionally hands the incident over to another team, which makes it
     * {@code OPEN} again for that team.
     *
     * @param targetTeamId new owning team, or null to keep the current one
     */
    public void escalate(Severity newSeverity, UUID targetTeamId, Instant now) {
        Objects.requireNonNull(newSeverity, "severity");
        requireActive();
        if (!newSeverity.isHigherThan(severity)) {
            throw new BusinessRuleException("An escalation must raise severity above " + severity);
        }
        changeSeverityAndTeam(newSeverity, targetTeamId, now);
    }

    /**
     * De-escalation: severity must go down. The team keeps the incident; only an escalation hands it over.
     */
    public void deEscalate(Severity newSeverity, Instant now) {
        Objects.requireNonNull(newSeverity, "severity");
        requireActive();
        if (!severity.isHigherThan(newSeverity)) {
            throw new BusinessRuleException("A de-escalation must lower severity below " + severity);
        }
        severity = newSeverity;
        updatedAt = now;
    }

    public Comment addComment(UUID authorId, String text, Instant now) {
        requireActive();
        Comment comment = new Comment(this, authorId, text, now);
        comments.add(comment);
        updatedAt = now;
        return comment;
    }

    public IncidentView toView() {
        return new IncidentView(id, title, description, categoryId, categoryName, teamId, reporterId, severity,
                status, createdAt, updatedAt, acknowledgedAt, resolvedAt, resolvedBy, resolutionNote, closedAt,
                comments.stream().map(Comment::toView).toList());
    }

    public IncidentSummary toSummary() {
        return new IncidentSummary(id, title, teamId, reporterId, severity, status, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    private void changeSeverityAndTeam(Severity newSeverity, UUID targetTeamId, Instant now) {
        if (targetTeamId != null) {
            reassign(targetTeamId, now);
        }
        severity = newSeverity;
        updatedAt = now;
    }

    private void requireActive() {
        if (!status.isActive()) {
            throw new BusinessRuleException("Incident " + id + " is " + status + " and can no longer change");
        }
    }
}
