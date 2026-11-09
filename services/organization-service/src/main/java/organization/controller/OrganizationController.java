package organization.controller;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;

import organization.model.TeamView;
import organization.service.OrganizationService;
import organization.web.ActingUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public endpoint of organization-service, on its own port (no API gateway, ADR D13):
 * {@code GET /teams} — list teams. Same path, body and status codes as in the monolith.
 *
 * <p>The caller is the {@value ActingUser#HEADER} header (user id or username) and must be an active user.
 */
@RestController
@Transactional(readOnly = true)
public class OrganizationController {

    private final OrganizationService organization;

    public OrganizationController(OrganizationService organization) {
        this.organization = organization;
    }

    /**
     * All teams, archived ones included, by name, each with its active members.
     */
    @GetMapping("/teams")
    @Operation(tags = OpenApiConfig.TEAMS, summary = "List teams", description = "All teams by name, archived ones included, each with its "
            + "active members and their team role.")
    public List<TeamView> listTeams(@ActingUser UUID actorId) {
        organization.getActiveActor(actorId);
        return organization.listTeams();
    }
}
