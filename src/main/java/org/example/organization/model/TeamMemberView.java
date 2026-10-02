package org.example.organization.model;

import java.util.UUID;

import org.example.common.model.TeamRole;

public record TeamMemberView(UUID userId, String name, String email, TeamRole role) {
}
