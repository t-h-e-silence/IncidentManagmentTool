package org.example.incidents.model;

import java.time.Instant;
import java.util.UUID;

import org.example.common.model.Severity;

/**
 * One line of a list (my incidents, team queue).
 */
public record IncidentSummary(UUID id, String title, UUID teamId, Severity severity, IncidentStatus status,
                              Instant createdAt) {
}
