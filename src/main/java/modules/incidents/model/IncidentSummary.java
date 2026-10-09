package modules.incidents.model;

import java.time.Instant;
import java.util.UUID;

import modules.common.model.Severity;

/**
 * One line of a list (all incidents, a team's or a reporter's incidents, team queue).
 */
public record IncidentSummary(UUID id, String title, UUID teamId, UUID reporterId,
                              Severity severity, IncidentStatus status,
                              Instant createdAt) {
}
