package audit.service;

import java.util.Optional;
import java.util.UUID;

import audit.model.Actor;
import audit.web.ActingUserLookup;
import audit.web.exception.ServiceUnavailableException;
import audit.web.exception.UnauthenticatedException;

/**
 * The organization data audit-service needs, read from organization-service's {@code /internal/**} API and cached
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
}
