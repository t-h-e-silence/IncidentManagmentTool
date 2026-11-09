package escalations.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import escalations.exception.NotFoundException;
import escalations.exception.ServiceUnavailableException;
import escalations.model.Recipient;
import escalations.model.Team;

/**
 * The organization data escalation-service needs for the escalation emails, read from organization-service's
 * {@code /internal/**} API and cached in Redis (ADR D6). Every method may throw {@link ServiceUnavailableException}
 * when organization-service cannot answer and nothing is cached.
 */
public interface OrganizationClient {

    /**
     * A team with its active members, by name.
     *
     * @throws NotFoundException if the team is unknown
     */
    Team getTeam(UUID teamId);

    /**
     * Active admins, by name; recipients when a team has no active members.
     */
    List<Recipient> getActiveAdmins();

    /**
     * The user's contact, or empty if the user is unknown, deactivated or SYSTEM.
     */
    Optional<Recipient> findRecipient(UUID userId);
}
