package org.example.organization.model;

import java.util.UUID;

/**
 * An active category and the team responsible for its incidents.
 */
public record CategoryRouting(UUID categoryId, String categoryName, UUID teamId) {
}
