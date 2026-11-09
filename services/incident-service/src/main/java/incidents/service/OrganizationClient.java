package incidents.service;

import java.util.Optional;
import java.util.UUID;

import incidents.model.Actor;
import incidents.model.CategoryRouting;
import incidents.model.Team;
import incidents.web.ActingUserLookup;
import incidents.web.exception.BusinessRuleException;
import incidents.web.exception.NotFoundException;
import incidents.web.exception.ServiceUnavailableException;
import incidents.web.exception.UnauthenticatedException;

/**
 * The organization data incident-service needs, read from organization-service's {@code /internal/**} API and cached
 * in Redis (ADR D6). Every method may throw {@link ServiceUnavailableException} when organization-service cannot
 * answer and nothing is cached.
 */
public interface OrganizationClient extends ActingUserLookup {

    /**
     * Who is calling: id, name, system role and non-archived team ids.
     *
     * @throws UnauthenticatedException if the user is unknown, deactivated or SYSTEM
     */
    Actor getActiveActor(UUID userId);

    /**
     * The id of a user given by id or by username, whether the user may act or not; empty if no user has that
     * username.
     */
    @Override
    Optional<UUID> findUserId(String userIdOrUsername);

    /**
     * @throws NotFoundException if the category is unknown or inactive
     */
    CategoryRouting getRouting(UUID categoryId);

    /**
     * @throws NotFoundException if the team is unknown
     */
    Team getTeam(UUID teamId);

    /**
     * @throws BusinessRuleException if the team is unknown or archived
     */
    void requireActiveTeam(UUID teamId);
}
