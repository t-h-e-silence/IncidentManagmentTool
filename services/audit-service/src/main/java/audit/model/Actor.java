package audit.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The active user calling an endpoint (also the body of {@code GET /internal/actors/{user}}), with what modules need for permission checks.
 *
 * @param teamIds non-archived teams the user is a member of
 */
public record Actor(UUID id, String name, SystemRole role, Set<UUID> teamIds) {

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
