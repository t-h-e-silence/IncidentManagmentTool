package org.example.incidents.model;

/**
 * {@code OPEN -> IN_PROGRESS -> RESOLVED}, or {@code OPEN -> RESOLVED} (e.g. a duplicate). {@code RESOLVED} is final.
 */
public enum IncidentStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED
}
