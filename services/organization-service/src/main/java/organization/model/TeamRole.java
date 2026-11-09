package organization.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Role of a user within one team. In v1 both roles have the same rights.
 */
@Schema(description = """
        Role of a user within one team: `RESPONDER` or `TEAM_LEAD`. Both have the same rights for now.""")
public enum TeamRole {
    RESPONDER,
    TEAM_LEAD
}
