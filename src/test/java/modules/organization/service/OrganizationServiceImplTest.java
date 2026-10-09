package modules.organization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import modules.common.exception.BusinessRuleException;
import modules.common.exception.NotFoundException;
import modules.common.exception.UnauthenticatedException;
import modules.common.model.Actor;
import modules.common.model.Recipient;
import modules.common.model.SystemRole;
import modules.common.model.TeamRole;
import modules.organization.model.Category;
import modules.organization.model.Team;
import modules.organization.model.TeamMemberView;
import modules.organization.model.TeamView;
import modules.organization.model.User;
import modules.organization.repository.CategoryRepository;
import modules.organization.repository.TeamRepository;
import modules.organization.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock
    UserRepository users;
    @Mock
    TeamRepository teams;
    @Mock
    CategoryRepository categories;
    @InjectMocks
    OrganizationServiceImpl service;

    private final User alice = new User(UUID.randomUUID(), "Alice", "alice@example.com", SystemRole.USER);
    private final User dan = new User(UUID.randomUUID(), "Dan", "dan@example.com", SystemRole.USER);
    private final Team database = new Team(UUID.randomUUID(), "Database", alice.getId(), TeamRole.TEAM_LEAD);

    @Test
    void activeActorCarriesRoleAndTeams() {
        when(users.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(teams.findActiveTeamIdsOfUser(alice.getId())).thenReturn(List.of(database.getId()));

        Actor actor = service.getActiveActor(alice.getId());

        assertThat(actor.name()).isEqualTo("Alice");
        assertThat(actor.isMemberOf(database.getId())).isTrue();
    }

    @Test
    void unknownDeactivatedOrSystemUserIsRejected() {
        User system = new User(UUID.randomUUID(), "System", "system@ims.invalid", SystemRole.SYSTEM);
        ReflectionTestUtils.setField(dan, "active", false);
        UUID unknown = UUID.randomUUID();
        when(users.findById(unknown)).thenReturn(Optional.empty());
        when(users.findById(dan.getId())).thenReturn(Optional.of(dan));
        when(users.findById(system.getId())).thenReturn(Optional.of(system));

        for (UUID id : List.of(unknown, dan.getId(), system.getId())) {
            assertThatThrownBy(() -> service.getActiveActor(id)).isInstanceOf(UnauthenticatedException.class);
        }
    }

    @Test
    void userIsFoundByIdOrUsername() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThat(service.findUserId(alice.getId().toString())).contains(alice.getId());
        assertThat(service.findUserId(" Alice ")).contains(alice.getId());
        assertThat(service.findUserId("nobody")).isEmpty();
        assertThat(service.findUserId(" ")).isEmpty();
    }

    @Test
    void routingOfInactiveOrUnknownCategoryIsNotFound() {
        Category vpn = new Category(UUID.randomUUID(), "VPN", database.getId());
        ReflectionTestUtils.setField(vpn, "active", false);
        when(categories.findById(vpn.toRouting().categoryId())).thenReturn(Optional.of(vpn));

        assertThatThrownBy(() -> service.getRouting(vpn.toRouting().categoryId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getRouting(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void membersAreActiveUsersOnlyByName() {
        database.addMember(dan.getId(), TeamRole.RESPONDER);
        ReflectionTestUtils.setField(dan, "active", false);
        when(teams.findById(database.getId())).thenReturn(Optional.of(database));
        when(users.findAllById(any())).thenReturn(List.of(alice, dan));

        assertThat(service.getActiveMembers(database.getId()))
                .containsExactly(new Recipient(alice.getId(), "Alice", "alice@example.com"));
        assertThat(service.getTeam(database.getId()).members()).extracting(TeamMemberView::role)
                .containsExactly(TeamRole.TEAM_LEAD);
    }

    @Test
    void listsAllTeamsIncludingArchivedWithActiveMembers() {
        Team platform = new Team(UUID.randomUUID(), "Platform", dan.getId(), TeamRole.TEAM_LEAD);
        ReflectionTestUtils.setField(platform, "archivedAt", Instant.now());
        when(teams.findAllByOrderByName()).thenReturn(List.of(database, platform));
        when(users.findAllById(any())).thenReturn(List.of(alice, dan));

        List<TeamView> all = service.listTeams();

        assertThat(all).extracting(TeamView::name).containsExactly("Database", "Platform");
        assertThat(all).extracting(TeamView::archived).containsExactly(false, true);
        assertThat(all.get(0).members()).extracting(TeamMemberView::name).containsExactly("Alice");
    }

    @Test
    void archivedOrUnknownTeamIsNotActive() {
        ReflectionTestUtils.setField(database, "archivedAt", Instant.now());
        when(teams.findById(database.getId())).thenReturn(Optional.of(database));

        assertThatThrownBy(() -> service.requireActiveTeam(database.getId()))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.requireActiveTeam(UUID.randomUUID()))
                .isInstanceOf(BusinessRuleException.class);
    }
}
