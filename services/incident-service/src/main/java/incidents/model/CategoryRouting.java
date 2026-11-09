package incidents.model;

import java.util.UUID;

/**
 * An active category and the team responsible for its incidents, as organization-service answers it.
 */
public record CategoryRouting(UUID categoryId, String categoryName, UUID teamId) {
}
