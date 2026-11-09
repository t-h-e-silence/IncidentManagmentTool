package audit.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import audit.model.Actor;
import audit.model.AuditAction;
import audit.model.AuditEntityType;
import audit.model.AuditEntryView;
import audit.model.SystemRole;
import audit.service.AuditService;
import audit.service.OrganizationClient;
import audit.web.ActingUser;
import audit.web.exception.UnauthenticatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP side of {@link AuditController}: route, the {@value ActingUser#HEADER} header, body and status codes.
 */
@WebMvcTest(AuditController.class)
class AuditControllerHttpTest {

    private static final String USER = ActingUser.HEADER;
    private static final UUID DAN = UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID BOB = UUID.fromString("20000000-0000-0000-0000-000000000003");

    @Autowired
    MockMvc mvc;
    @MockitoBean
    OrganizationClient organization;
    @MockitoBean
    AuditService audit;

    private final UUID incidentId = UUID.randomUUID();

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
    void historyIsTheTimelineOldestFirst() throws Exception {
        when(organization.getActiveActor(DAN)).thenReturn(new Actor(DAN, "Dan Dba", SystemRole.USER, Set.of()));
        when(audit.getIncidentTimeline(incidentId)).thenReturn(List.of(new AuditEntryView(UUID.randomUUID(), DAN,
                AuditAction.INCIDENT_CREATED, AuditEntityType.INCIDENT, incidentId, incidentId,
                Map.of("severity", "SEV2"), "corr-1", Instant.parse("2026-10-02T10:00:00Z"))));

        mvc.perform(get("/incidents/{id}/history", incidentId).header(USER, "dan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("INCIDENT_CREATED"))
                .andExpect(jsonPath("$[0].details.severity").value("SEV2"))
                .andExpect(jsonPath("$[0].correlationId").value("corr-1"));
    }

    @Test
    void nothingRecordedIs404() throws Exception {
        when(organization.getActiveActor(DAN)).thenReturn(new Actor(DAN, "Dan Dba", SystemRole.USER, Set.of()));
        when(audit.getIncidentTimeline(incidentId)).thenReturn(List.of());

        mvc.perform(get("/incidents/{id}/history", incidentId).header(USER, "dan"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inactiveCallerIs401() throws Exception {
        when(organization.getActiveActor(BOB)).thenThrow(new UnauthenticatedException("User is unknown or inactive"));

        mvc.perform(get("/incidents/{id}/history", incidentId).header(USER, "bob"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/incidents/{id}/history", incidentId))
                .andExpect(status().isUnauthorized());
    }
}
