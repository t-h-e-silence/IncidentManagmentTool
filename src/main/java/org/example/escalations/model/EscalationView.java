package org.example.escalations.model;

import java.time.Instant;
import java.util.UUID;

import org.example.common.model.Severity;

public record EscalationView(UUID id, UUID incidentId, UUID actorId, String reason, Severity fromSeverity,
                             Severity toSeverity, UUID fromTeamId, UUID toTeamId, Instant escalatedAt) {
}
