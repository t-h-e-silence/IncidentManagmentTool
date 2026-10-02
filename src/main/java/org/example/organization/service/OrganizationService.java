package org.example.organization.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.common.model.Actor;
import org.example.common.model.Recipient;
import org.example.organization.model.CategoryRouting;
import org.example.organization.model.CategoryView;
import org.example.organization.model.TeamView;

/**
 * Users, teams, memberships and category routing.
 */
public interface OrganizationService {

    /**
     * Who is calling: id, name, system role and non-archived team ids.
     *
     * @throws org.example.common.exception.UnauthenticatedException if the user is unknown, deactivated or SYSTEM
     */
    Actor getActiveActor(UUID userId);

    /**
     * Active categories a reporter can choose, by name.
     */
    List<CategoryView> listActiveCategories();

    /**
     * @throws org.example.common.exception.NotFoundException if the category is unknown or inactive
     */
    CategoryRouting getRouting(UUID categoryId);

    /**
     * @throws org.example.common.exception.NotFoundException if the team is unknown
     */
    TeamView getTeam(UUID teamId);

    /**
     * @throws org.example.common.exception.BusinessRuleException if the team is unknown or archived
     */
    void requireActiveTeam(UUID teamId);

    /**
     * Active members of the team, by name.
     */
    List<Recipient> getActiveMembers(UUID teamId);

    /**
     * Active admins, by name; recipients when a team has no active members.
     */
    List<Recipient> getActiveAdmins();

    /**
     * The user's contact, or empty if the user is unknown, deactivated or SYSTEM.
     */
    Optional<Recipient> findRecipient(UUID userId);
}
