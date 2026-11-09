package organization.controller;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import organization.model.Actor;
import organization.model.CategoryRouting;
import organization.model.Recipient;
import organization.model.TeamView;
import organization.model.UserId;
import organization.service.OrganizationService;
import organization.web.exception.NotFoundException;
import organization.web.exception.UnauthenticatedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organization data for the other services (ADR D1, D6). Not part of the public API; callers cache the answers in Redis. No caller header: the calling service has already resolved its caller.
 *
 * <ul>
 *   <li>{@code GET /internal/actors/{user}} — who may act: 401 if unknown, deactivated or SYSTEM</li>
 *   <li>{@code GET /internal/users/{user}/id} — the id of a user given by id or username, active or not</li>
 *   <li>{@code GET /internal/categories/{id}/routing} — the team of an active category</li>
 *   <li>{@code GET /internal/teams/{id}} — a team with its archived flag and active members</li>
 *   <li>{@code GET /internal/admins} — active admins, recipients when a team has no active members</li>
 *   <li>{@code GET /internal/users/{id}/recipient} — the contact of a user who may be emailed</li>
 * </ul>
 *
 * <p>Errors use the same status codes as the public API, so a client can turn them back into the same exceptions.
 */
@RestController
@RequestMapping("/internal")
@Transactional(readOnly = true)
public class InternalOrganizationController {

    private final OrganizationService organization;

    public InternalOrganizationController(OrganizationService organization) {
        this.organization = organization;
    }

    /**
     * @param user user id or username
     */
    @GetMapping("/actors/{user}")
    @Operation(tags = OpenApiConfig.INTERNAL, summary = "Active actor", description = "Id, name, system role and "
            + "non-archived team ids of a user who may act. 401 if the user is unknown, deactivated or SYSTEM.")
    public Actor getActiveActor(
            @Parameter(description = "Username or user id", example = "bob") @PathVariable String user) {
        UUID userId = organization.findUserId(user)
                .orElseThrow(() -> new UnauthenticatedException("Unknown user " + user));
        return organization.getActiveActor(userId);
    }

    /**
     * @param user user id or username
     */
    @GetMapping("/users/{user}/id")
    @Operation(tags = OpenApiConfig.INTERNAL, summary = "User id", description = "The id of a user given by id or "
            + "username, whether the user may act or not (e.g. to list a deactivated reporter's incidents). An id is "
            + "returned as it is. 404 if no user has that username.")
    public UserId findUserId(
            @Parameter(description = "Username or user id", example = "bob") @PathVariable String user) {
        return organization.findUserId(user)
                .map(UserId::new)
                .orElseThrow(() -> new NotFoundException("User " + user + " not found"));
    }

    @GetMapping("/categories/{categoryId}/routing")
    @Operation(tags = OpenApiConfig.INTERNAL, summary = "Category routing", description = "The team responsible for "
            + "an active category. 404 if the category is unknown or inactive.")
    public CategoryRouting getRouting(
            @Parameter(description = "Category id", example = "30000000-0000-0000-0000-000000000003")
            @PathVariable UUID categoryId) {
        return organization.getRouting(categoryId);
    }

    @GetMapping("/teams/{teamId}")
    @Operation(tags = OpenApiConfig.INTERNAL, summary = "Team", description = "A team, archived or not, with its active "
            + "members by name. 404 if the team is unknown.")
    public TeamView getTeam(
            @Parameter(description = "Team id", example = "10000000-0000-0000-0000-000000000002")
            @PathVariable UUID teamId) {
        return organization.getTeam(teamId);
    }

    @GetMapping("/admins")
    @Operation(tags = OpenApiConfig.INTERNAL, summary = "Active admins", description = "By name; they receive the "
            + "emails of teams without active members.")
    public List<Recipient> getActiveAdmins() {
        return organization.getActiveAdmins();
    }

    @GetMapping("/users/{userId}/recipient")
    @Operation(tags = OpenApiConfig.INTERNAL, summary = "Recipient", description = "Name and email of a user who may "
            + "be emailed. 404 if the user is unknown, deactivated or SYSTEM.")
    public Recipient getRecipient(
            @Parameter(description = "User id", example = "20000000-0000-0000-0000-000000000003")
            @PathVariable UUID userId) {
        return organization.findRecipient(userId)
                .orElseThrow(() -> new NotFoundException("No active user " + userId));
    }
}
