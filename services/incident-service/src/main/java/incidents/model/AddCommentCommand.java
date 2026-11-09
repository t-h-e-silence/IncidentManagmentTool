package incidents.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /incidents/{id}/comments}.
 */
@Schema(description = "Body of POST /incidents/{id}/comments")
public record AddCommentCommand(@Schema(description = "Comment text (≤ 5000); by the owning team or the reporter, while the incident is OPEN, IN_PROGRESS or IN_REVIEW", example = "Also affects the mobile app", requiredMode = Schema.RequiredMode.REQUIRED) String text) {
}
