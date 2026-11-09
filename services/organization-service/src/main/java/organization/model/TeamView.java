package organization.model;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param members active members only, by name
 */
@Schema(description = "A responder team with its active members")
public record TeamView(
        @Schema(description = "Team id; incidents keep it, so a team is archived, never deleted", example = "10000000-0000-0000-0000-000000000002") UUID id,
        @Schema(description = "Unique team name (≤ 200)", example = "Database") String name,
        @Schema(description = "True if the team was archived: it keeps its incidents but can no longer receive new ones or be handed incidents over", example = "false") boolean archived,
        @Schema(description = "Active members only, by name; deactivated users are left out") List<TeamMemberView> members) {
}
