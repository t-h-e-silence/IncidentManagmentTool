package audit.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * An event already turned into audit entries; RabbitMQ delivers at least once, so a repeat is skipped (ADR D4).
 * Stored in the same transaction as the entries.
 */
@Entity
@Table(name = "processed_event", schema = "audit")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    protected ProcessedEvent() {
        // for JPA
        isNew = false;
    }

    public ProcessedEvent(UUID eventId, Instant processedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    /**
     * Always inserted, never merged: a concurrent duplicate then fails on the primary key and is retried as a repeat.
     */
    @Override
    public boolean isNew() {
        return isNew;
    }
}
