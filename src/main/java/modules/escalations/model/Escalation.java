package modules.escalations.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import modules.common.model.Severity;
import org.hibernate.annotations.Immutable;

/**
 * One escalation or de-escalation of an incident. Never changed after it is recorded.
 */
@Entity
@Immutable
@Table(name = "escalation", schema = "escalations")
public class Escalation {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(nullable = false, length = EscalationRecord.REASON_MAX_LENGTH)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_severity", nullable = false, length = 10)
    private Severity fromSeverity;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_severity", nullable = false, length = 10)
    private Severity toSeverity;

    @Column(name = "from_team_id", nullable = false)
    private UUID fromTeamId;

    @Column(name = "to_team_id", nullable = false)
    private UUID toTeamId;

    @Column(name = "escalated_at", nullable = false)
    private Instant escalatedAt;

    protected Escalation() {
        // for JPA
    }

    public Escalation(EscalationRecord record, Instant escalatedAt) {
        this.id = UUID.randomUUID();
        this.incidentId = record.incidentId();
        this.actorId = record.actorId();
        this.reason = record.reason();
        this.fromSeverity = record.fromSeverity();
        this.toSeverity = record.toSeverity();
        this.fromTeamId = record.fromTeamId();
        this.toTeamId = record.toTeamId();
        this.escalatedAt = escalatedAt;
    }
}
