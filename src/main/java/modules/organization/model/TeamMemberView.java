package modules.organization.model;

import java.util.UUID;

import modules.common.model.TeamRole;

public record TeamMemberView(UUID userId, String name, String email, TeamRole role) {
}
