package modules.common.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The active user calling the controller, with what modules need for permission checks.
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

    public boolean isAdmin() {
        return role == SystemRole.ADMIN;
    }
}
