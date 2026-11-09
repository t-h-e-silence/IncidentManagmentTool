package incidents.model;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A comment on an incident")
public record CommentView(
        @Schema(description = "Comment id", example = "50000000-0000-0000-0000-000000000001") UUID id,
        @Schema(description = "Incident it belongs to", example = "40000000-0000-0000-0000-000000000001") UUID incidentId,
        @Schema(description = "User who wrote it: a member of the owning team or the reporter", example = "20000000-0000-0000-0000-000000000003") UUID authorId,
        @Schema(description = "Text (≤ 5000); comments are never edited or deleted", example = "Also affects the mobile app") String text,
        @Schema(description = "When it was written (UTC)", example = "2026-10-02T09:05:00Z") Instant createdAt) {
}
