package incidents.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;


/**
 * @param closedAt when it was closed or cancelled
 */
@Schema(description = "An incident with all its fields and comments")
public record IncidentView(
        @Schema(description = "Incident id", example = "40000000-0000-0000-0000-000000000001") UUID id,
        @Schema(description = "Short name (≤ 200)", example = "Checkout is slow") String title,
        @Schema(description = "Free text (≤ 5000); empty string if none was given", example = "Pages take 10 s to load") String description,
        @Schema(description = "Category the reporter chose; it decided the first owning team", example = "30000000-0000-0000-0000-000000000003") UUID categoryId,
        @Schema(description = "Category name at the time of reporting (snapshot)", example = "Database") String categoryName,
        @Schema(description = "Team that owns the incident now; changes only by an escalation with hand-over", example = "10000000-0000-0000-0000-000000000002") UUID teamId,
        @Schema(description = "User who reported it", example = "20000000-0000-0000-0000-000000000003") UUID reporterId,
        @Schema(description = "Current severity", example = "SEV2") Severity severity,
        @Schema(description = "Current lifecycle status", example = "OPEN") IncidentStatus status,
        @Schema(description = "When it was reported (UTC)", example = "2026-10-02T09:00:00Z") Instant createdAt,
        @Schema(description = "Last change of any field or a new comment (UTC)", example = "2026-10-02T09:05:00Z") Instant updatedAt,
        @Schema(description = "When the owning team first moved it to IN_PROGRESS; cleared on hand-over to another team", nullable = true) Instant acknowledgedAt,
        @Schema(description = "When it was resolved; cleared when reopened", nullable = true) Instant resolvedAt,
        @Schema(description = "User who resolved it; cleared when reopened", nullable = true) UUID resolvedBy,
        @Schema(description = "Reason given when resolving (≤ 2000); cleared when reopened", nullable = true) String resolutionNote,
        @Schema(description = "When it was closed or cancelled", nullable = true) Instant closedAt,
        @Schema(description = "Comments, oldest first") List<CommentView> comments) {
}
