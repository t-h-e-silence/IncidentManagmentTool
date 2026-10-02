package org.example.organization.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.common.exception.BusinessRuleException;
import org.example.common.exception.NotFoundException;
import org.example.common.exception.UnauthenticatedException;
import org.example.common.model.Actor;
import org.example.common.model.Recipient;
import org.example.common.model.SystemRole;
import org.example.organization.model.Category;
import org.example.organization.model.CategoryRouting;
import org.example.organization.model.CategoryView;
import org.example.organization.model.Team;
import org.example.organization.model.TeamMemberView;
import org.example.organization.model.TeamView;
import org.example.organization.model.User;
import org.example.organization.repository.CategoryRepository;
import org.example.organization.repository.TeamRepository;
import org.example.organization.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrganizationServiceImpl implements OrganizationService {

    private final UserRepository users;
    private final TeamRepository teams;
    private final CategoryRepository categories;

    public OrganizationServiceImpl(UserRepository users, TeamRepository teams, CategoryRepository categories) {
        this.users = users;
        this.teams = teams;
        this.categories = categories;
    }

    @Override
    public Actor getActiveActor(UUID userId) {
        User user = users.findById(userId)
                .filter(User::canAct)
                .orElseThrow(() -> new UnauthenticatedException("User " + userId + " is unknown or inactive"));
        return new Actor(user.getId(), user.getName(), user.getSystemRole(),
                new HashSet<>(teams.findActiveTeamIdsOfUser(userId)));
    }

    @Override
    public List<CategoryView> listActiveCategories() {
        return categories.findByActiveTrueOrderByName().stream().map(Category::toView).toList();
    }

    @Override
    public CategoryRouting getRouting(UUID categoryId) {
        return categories.findById(categoryId)
                .filter(Category::isActive)
                .map(Category::toRouting)
                .orElseThrow(() -> new NotFoundException("Category " + categoryId + " not found"));
    }

    @Override
    public TeamView getTeam(UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() -> new NotFoundException("Team " + teamId + " not found"));
        Map<UUID, User> activeUsers = activeUsersOf(team);
        List<TeamMemberView> members = team.getMembers().entrySet().stream()
                .filter(member -> activeUsers.containsKey(member.getKey()))
                .map(member -> {
                    User user = activeUsers.get(member.getKey());
                    return new TeamMemberView(user.getId(), user.getName(), user.getEmail(), member.getValue());
                })
                .sorted(Comparator.comparing(TeamMemberView::name))
                .toList();
        return new TeamView(team.getId(), team.getName(), team.isArchived(), members);
    }

    @Override
    public void requireActiveTeam(UUID teamId) {
        if (teams.findById(teamId).filter(team -> !team.isArchived()).isEmpty()) {
            throw new BusinessRuleException("Team " + teamId + " does not exist or is archived");
        }
    }

    @Override
    public List<Recipient> getActiveMembers(UUID teamId) {
        return teams.findById(teamId)
                .map(team -> activeUsersOf(team).values().stream()
                        .map(OrganizationServiceImpl::toRecipient)
                        .sorted(Comparator.comparing(Recipient::name))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<Recipient> getActiveAdmins() {
        return users.findBySystemRoleAndActiveTrueOrderByName(SystemRole.ADMIN).stream()
                .map(OrganizationServiceImpl::toRecipient)
                .toList();
    }

    @Override
    public Optional<Recipient> findRecipient(UUID userId) {
        return users.findById(userId).filter(User::canAct).map(OrganizationServiceImpl::toRecipient);
    }

    /**
     * Members of the team who may act, loaded in one query.
     */
    private Map<UUID, User> activeUsersOf(Team team) {
        return users.findAllById(team.getMembers().keySet()).stream()
                .filter(User::canAct)
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static Recipient toRecipient(User user) {
        return new Recipient(user.getId(), user.getName(), user.getEmail());
    }
}
