package organization.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import organization.model.Actor;
import organization.model.CategoryRouting;
import organization.model.Recipient;
import organization.model.SystemRole;
import organization.model.TeamMemberView;
import organization.model.TeamRole;
import organization.model.TeamView;
import organization.service.OrganizationService;
import organization.web.ActingUser;
import organization.web.exception.NotFoundException;
import organization.web.exception.UnauthenticatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP side of organization-service: routes, the {@value ActingUser#HEADER} header, bodies and status codes,
 * with the caller resolution and error mapping of {@code organization.web}.
 */
@WebMvcTest({OrganizationController.class, InternalOrganizationController.class})
class OrganizationControllerHttpTest {

    private static final String USER = ActingUser.HEADER;

    private static final UUID BOB = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID DAN = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID DATABASE = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired
    MockMvc mvc;
    @MockitoBean
    OrganizationService organization;

    private final Actor dan = new Actor(DAN, "Dan Dba", SystemRole.USER, Set.of(DATABASE));
    private final TeamView database = new TeamView(DATABASE, "Database", false,
            List.of(new TeamMemberView(DAN, "Dan Dba", "dan@example.com", TeamRole.RESPONDER)));

    /**
     * Like the real organization service: ids pass through, the demo usernames are known.
     */
    @BeforeEach
    void knownUsers() {
        Map<String, UUID> usernames = Map.of("bob", BOB, "dan", DAN);
        when(organization.findUserId(anyString())).thenAnswer(invocation -> {
            String user = invocation.getArgument(0);
            try {
                return Optional.of(UUID.fromString(user));
            } catch (IllegalArgumentException e) {
                return Optional.ofNullable(usernames.get(user));
            }
        });
    }

    @Test
    void listsTeamsForAnActiveCaller() throws Exception {
        when(organization.getActiveActor(DAN)).thenReturn(dan);
        when(organization.listTeams()).thenReturn(List.of(database));

        mvc.perform(get("/teams").header(USER, "dan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Database"))
                .andExpect(jsonPath("$[0].archived").value(false))
                .andExpect(jsonPath("$[0].members[0].role").value("RESPONDER"));
    }

    @Test
    void missingOrUnknownCallerIs401() throws Exception {
        mvc.perform(get("/teams"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Header X-User-Id (user id or username) is missing"));
        mvc.perform(get("/teams").header(USER, "nobody"))
                .andExpect(status().isUnauthorized());

        verify(organization, never()).listTeams();
    }

    @Test
    void inactiveCallerIs401() throws Exception {
        when(organization.getActiveActor(BOB)).thenThrow(new UnauthenticatedException("User is unknown or inactive"));

        mvc.perform(get("/teams").header(USER, BOB.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalActorByUsernameNeedsNoHeader() throws Exception {
        when(organization.getActiveActor(DAN)).thenReturn(dan);

        mvc.perform(get("/internal/actors/dan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DAN.toString()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.teamIds[0]").value(DATABASE.toString()))
                .andExpect(jsonPath("$.admin").doesNotExist());
        mvc.perform(get("/internal/actors/nobody"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalUserIdIs404ForUnknownUsername() throws Exception {
        mvc.perform(get("/internal/users/bob/id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(BOB.toString()));
        mvc.perform(get("/internal/users/nobody/id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalRoutingAndTeam() throws Exception {
        UUID categoryId = UUID.randomUUID();
        when(organization.getRouting(categoryId)).thenReturn(new CategoryRouting(categoryId, "Database", DATABASE));
        when(organization.getTeam(DATABASE)).thenReturn(database);
        UUID unknown = UUID.randomUUID();
        when(organization.getTeam(unknown)).thenThrow(new NotFoundException("Team " + unknown + " not found"));

        mvc.perform(get("/internal/categories/{id}/routing", categoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(DATABASE.toString()));
        mvc.perform(get("/internal/teams/{id}", DATABASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].email").value("dan@example.com"));
        mvc.perform(get("/internal/teams/{id}", unknown))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/teams/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalRecipientsAndAdmins() throws Exception {
        when(organization.getActiveAdmins())
                .thenReturn(List.of(new Recipient(UUID.randomUUID(), "Ada Admin", "ada.admin@example.com")));
        when(organization.findRecipient(DAN)).thenReturn(Optional.of(new Recipient(DAN, "Dan Dba", "dan@example.com")));
        when(organization.findRecipient(BOB)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/admins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ada Admin"));
        mvc.perform(get("/internal/users/{id}/recipient", DAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("dan@example.com"));
        mvc.perform(get("/internal/users/{id}/recipient", BOB))
                .andExpect(status().isNotFound());
    }
}
