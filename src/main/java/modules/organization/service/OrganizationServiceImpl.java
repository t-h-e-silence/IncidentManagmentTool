package modules.organization.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import modules.common.exception.BusinessRuleException;
import modules.common.exception.NotFoundException;
import modules.common.exception.UnauthenticatedException;
import modules.common.model.Actor;
import modules.common.model.Recipient;
import modules.common.model.SystemRole;
import modules.organization.model.Category;
import modules.organization.model.CategoryRouting;
import modules.organization.model.Team;
import modules.organization.model.TeamMemberView;
import modules.organization.model.TeamView;
import modules.organization.model.User;
import modules.organization.repository.CategoryRepository;
import modules.organization.repository.TeamRepository;
import modules.organization.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrganizationServiceImpl implements OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationServiceImpl.class);

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
                .orElseThrow(() -> {
                    log.warn("User {} is unknown, deactivated or the system user, so may not act", userId);
                    return new UnauthenticatedException("User " + userId + " is unknown or inactive");
                });
        return new Actor(user.getId(), user.getName(), user.getSystemRole(),
                new HashSet<>(teams.findActiveTeamIdsOfUser(userId)));
    }

    @Override
    public Optional<UUID> findUserId(String userIdOrUsername) {
        if (userIdOrUsername == null || userIdOrUsername.isBlank()) {
            return Optional.empty();
        }
        String value = userIdOrUsername.strip();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAnId) {
            Optional<UUID> id = users.findByUsername(value.toLowerCase(Locale.ROOT)).map(User::getId);
            if (id.isEmpty()) {
                log.debug("No user with username '{}'", value);
            }
            return id;
        }
    }

    /**
     * Says at startup who can use the system, and warns when nobody can (e.g. the {@code seed} profile was not used).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logUsersWhoCanAct() {
        List<String> names = users.findAll().stream()
                .filter(User::canAct)
                .map(user -> user.getUsername() != null ? user.getUsername() : user.getId().toString())
                .sorted()
                .toList();
        if (names.isEmpty()) {
            log.warn("No users can act: the database has no active users. For demo users (ada, alice, bob, carol, "
                    + "dan, erin) start the app with the 'seed' profile (-Dspring.profiles.active=seed)");
        } else {
            log.info("{} user(s) can act: {}", names.size(), names);
        }
    }

    @Override
    public CategoryRouting getRouting(UUID categoryId) {
        return categories.findById(categoryId)
                .filter(Category::isActive)
                .map(Category::toRouting)
                .orElseThrow(() -> new NotFoundException("Category " + categoryId + " not found"));
    }

    @Override
    public List<TeamView> listTeams() {
        return teams.findAllByOrderByName().stream().map(this::toView).toList();
    }

    @Override
    public TeamView getTeam(UUID teamId) {
        return toView(teams.findById(teamId).orElseThrow(() -> new NotFoundException("Team " + teamId + " not found")));
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

    private TeamView toView(Team team) {
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
