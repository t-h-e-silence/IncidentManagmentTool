package organization.model;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An active category and the team responsible for its incidents.
 */
@Schema(description = "An active category and the team responsible for its incidents")
public record CategoryRouting(
        @Schema(description = "Category id, chosen by the reporter", example = "30000000-0000-0000-0000-000000000003") UUID categoryId,
        @Schema(description = "Category name; incidents keep it as a snapshot", example = "Database") String categoryName,
        @Schema(description = "Team that owns new incidents of this category", example = "10000000-0000-0000-0000-000000000002") UUID teamId) {
}
