package audit.web;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the {@value ActingUser#HEADER} header into a user id. Implemented by {@link audit.service.OrganizationClient}, which asks organization-service.
 */
@FunctionalInterface
public interface ActingUserLookup {

    /**
     * The id of a user given by id or by username (e.g. {@code bob}); empty if no user has that username.
     * Says nothing about whether the user may act.
     */
    Optional<UUID> findUserId(String userIdOrUsername);
}
