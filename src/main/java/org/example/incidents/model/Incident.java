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
     * {@code OPEN -> IN_PROGRESS}.
     */
    public void acknowledge(Instant now) {
        if (status != IncidentStatus.OPEN) {
            throw new BusinessRuleException("Only an OPEN incident can be acknowledged, status is " + status);
        }
        status = IncidentStatus.IN_PROGRESS;
        acknowledgedAt = now;
        updatedAt = now;
    }

    /**
     * {@code OPEN | IN_PROGRESS -> RESOLVED}; final.
     */
    public void resolve(UUID resolvedBy, String note, Instant now) {
        requireNotResolved();
        String checkedNote = Text.require(note, "resolution note", NOTE_MAX_LENGTH);
        status = IncidentStatus.RESOLVED;
        this.resolvedBy = Objects.requireNonNull(resolvedBy, "resolvedBy");
        this.resolutionNote = checkedNote;
        resolvedAt = now;
        updatedAt = now;
    }

    public void changeSeverity(Severity newSeverity, Instant now) {
        Objects.requireNonNull(newSeverity, "severity");
        requireNotResolved();
        if (newSeverity == severity) {
            throw new BusinessRuleException("Severity is already " + severity);
        }
        severity = newSeverity;
        updatedAt = now;
    }

    /**
     * Hands the incident over to another team. The new team has not acknowledged it yet, so it is {@code OPEN} again.
     */
    public void reassign(UUID targetTeamId, Instant now) {
        Objects.requireNonNull(targetTeamId, "targetTeamId");
        requireNotResolved();
        if (targetTeamId.equals(teamId)) {
            throw new BusinessRuleException("Incident is already owned by team " + teamId);
        }
        teamId = targetTeamId;
        status = IncidentStatus.OPEN;
        acknowledgedAt = null;
        updatedAt = now;
    }

    public Comment addComment(UUID authorId, String text, Instant now) {
        requireNotResolved();
        Comment comment = new Comment(this, authorId, text, now);
        comments.add(comment);
        updatedAt = now;
        return comment;
    }

    public boolean isResolved() {
        return status == IncidentStatus.RESOLVED;
    }

    public IncidentView toView() {
        return new IncidentView(id, title, description, categoryId, categoryName, teamId, reporterId, severity,
                status, createdAt, updatedAt, acknowledgedAt, resolvedAt, resolvedBy, resolutionNote,
                comments.stream().map(Comment::toView).toList());
    }

    public IncidentSummary toSummary() {
        return new IncidentSummary(id, title, teamId, severity, status, createdAt);
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

    public Severity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    private void requireNotResolved() {
        if (status == IncidentStatus.RESOLVED) {
            throw new BusinessRuleException("Incident " + id + " is resolved and can no longer change");
        }
    }
}
