package incidents.model;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;


/**
 * One line of a list (all incidents, a team's or a reporter's incidents, team queue).
 */
@Schema(description = "One line of an incident list, newest first")
public record IncidentSummary(
        @Schema(description = "Incident id", example = "40000000-0000-0000-0000-000000000001") UUID id,
        @Schema(description = "Short name", example = "Checkout is slow") String title,
        @Schema(description = "Owning team", example = "10000000-0000-0000-0000-000000000002") UUID teamId,
        @Schema(description = "User who reported it", example = "20000000-0000-0000-0000-000000000003") UUID reporterId,
        @Schema(description = "Current severity", example = "SEV2") Severity severity,
        @Schema(description = "Current lifecycle status", example = "OPEN") IncidentStatus status,
        @Schema(description = "When it was reported (UTC)", example = "2026-10-02T09:00:00Z") Instant createdAt) {
}
