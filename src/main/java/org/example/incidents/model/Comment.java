package org.example.incidents.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.example.common.model.Text;

/**
 * A comment on an incident. Comments are never edited or deleted.
 */
@Entity
@Table(name = "comment", schema = "incidents")
public class Comment {

    public static final int TEXT_MAX_LENGTH = 5000;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false, length = TEXT_MAX_LENGTH)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Comment() {
        // for JPA
    }

    Comment(Incident incident, UUID authorId, String text, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.incident = Objects.requireNonNull(incident, "incident");
        this.authorId = Objects.requireNonNull(authorId, "authorId");
        this.text = Text.require(text, "text", TEXT_MAX_LENGTH);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public CommentView toView() {
        return new CommentView(id, incident.getId(), authorId, text, createdAt);
    }
}
