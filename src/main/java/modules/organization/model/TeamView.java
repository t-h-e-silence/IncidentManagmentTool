package modules.organization.model;

import java.util.List;
import java.util.UUID;

/**
 * @param members active members only, by name
 */
public record TeamView(UUID id, String name, boolean archived, List<TeamMemberView> members) {
}
