package org.example.organization.model;

import java.util.UUID;

import org.example.common.model.SystemRole;

public record UserView(UUID id, String name, String email, SystemRole systemRole, boolean active) {
}
