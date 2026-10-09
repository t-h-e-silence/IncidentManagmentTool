package modules.incidents.model;

/**
 * The incident before and after a change, so the caller can audit and notify about what changed.
 */
public record IncidentChange(IncidentView before, IncidentView after) {
}
