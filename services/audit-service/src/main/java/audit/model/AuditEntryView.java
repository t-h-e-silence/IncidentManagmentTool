package audit.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One immutable entry of an incident's history")
public record AuditEntryView(
        @Schema(description = "Entry id", example = "60000000-0000-0000-0000-000000000001") UUID id,
        @Schema(description = "User who made the change", example = "20000000-0000-0000-0000-000000000003") UUID actorId,
        @Schema(description = "What happened", example = "INCIDENT_CREATED") AuditAction action,
        @Schema(description = "Kind of thing that changed", example = "INCIDENT") AuditEntityType entityType,
        @Schema(description = "Id of the thing that changed: the incident, or the comment for COMMENT_ADDED", example = "40000000-0000-0000-0000-000000000001") UUID entityId,
        @Schema(description = "Incident the entry belongs to", example = "40000000-0000-0000-0000-000000000001") UUID incidentId,
        @Schema(description = "What changed, by action. INCIDENT_CREATED: team, severity. DETAILS_UPDATED: fromTitle, toTitle, descriptionChanged. ESCALATED / DE_ESCALATED: fromSeverity, toSeverity, fromTeam, toTeam, reason. STATUS_CHANGED: from, to, note (if given). COMMENT_ADDED: none. Ids, not personal data", example = "{\"team\": \"10000000-0000-0000-0000-000000000002\", \"severity\": \"SEV2\"}") Map<String, String> details,
        @Schema(description = "Shared by all entries one request produced, e.g. a PATCH that renames, escalates and acknowledges", example = "demo-0001-created") String correlationId,
        @Schema(description = "When the change happened in incident-service (UTC); the history is sorted by it", example = "2026-10-02T09:00:00Z") Instant occurredAt) {
}
