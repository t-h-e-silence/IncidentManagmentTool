package organization.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The active user calling an endpoint (also the body of {@code GET /internal/actors/{user}}), with what modules need for permission checks.
 *
 * @param teamIds non-archived teams the user is a member of
 */
@Schema(description = "A user who may act (active, not the system user), with what other services need for permission checks")
public record Actor(
        @Schema(description = "User id", example = "20000000-0000-0000-0000-000000000002") UUID id,
        @Schema(description = "Display name", example = "Alice Lead") String name,
        @Schema(description = "Global role", example = "USER") SystemRole role,
        @Schema(description = "Non-archived teams the user is a member of; decides which incidents the user may work on", example = "[\"10000000-0000-0000-0000-000000000002\", \"10000000-0000-0000-0000-000000000003\"]") Set<UUID> teamIds) {

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        teamIds = Set.copyOf(teamIds);
    }

    public boolean isMemberOf(UUID teamId) {
        return teamIds.contains(teamId);
    }

    @JsonIgnore
    public boolean isAdmin() {
        return role == SystemRole.ADMIN;
    }
}
