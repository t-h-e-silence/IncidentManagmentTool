package modules.audit.model;

import java.time.Instant;










































import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable entry of the audit log. There are no setters and Hibernate never updates it ({@link Immutable});
 * a mistake is fixed by appending another entry.
 */
@Entity
@Immutable
@Table(name = "audit_entry", schema = "audit")
public class AuditEntry {

    @Id
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private AuditEntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "incident_id")
    private UUID incidentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> details;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEntry() {
        // for JPA
    }

    public AuditEntry(AuditRecord record, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.actorId = record.actorId();
        this.action = record.action();
        this.entityType = record.entityType();
        this.entityId = record.entityId();
        this.incidentId = record.incidentId();
        this.details = record.details();
        this.correlationId = record.correlationId();
        this.occurredAt = occurredAt;
    }

    public AuditEntryView toView() {
        return new AuditEntryView(id, actorId, action, entityType, entityId, incidentId, details, correlationId,
                occurredAt);
    }
}
