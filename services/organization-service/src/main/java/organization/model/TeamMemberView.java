package organization.model;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;


@Schema(description = "An active member of a team")
public record TeamMemberView(
        @Schema(description = "User id; usable as X-User-Id", example = "20000000-0000-0000-0000-000000000005") UUID userId,
        @Schema(description = "Display name", example = "Dan Dba") String name,
        @Schema(description = "Email address, lower-case; where incident emails go", example = "dan@example.com") String email,
        @Schema(description = "Role in this team", example = "RESPONDER") TeamRole role) {
}
