package org.example.controller;

import static org.example.TestData.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.example.audit.service.AuditService;
import org.example.common.exception.ForbiddenException;
import org.example.common.exception.UnauthenticatedException;
import org.example.common.model.Severity;
import org.example.escalations.service.EscalationService;
import org.example.incidents.model.CommentView;
import org.example.incidents.model.IncidentChange;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentView;
import org.example.incidents.service.IncidentService;
import org.example.notifications.service.NotificationService;
import org.example.organization.model.CategoryRouting;
import org.example.organization.model.TeamView;
import org.example.organization.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP side of {@link IncidentManagementController}: routes, the {@value ActingUser#HEADER} header, bodies and
 * status codes. The orchestration itself is covered by {@link IncidentManagementControllerTest}.
 */
@WebMvcTest(IncidentManagementController.class)
class IncidentManagementControllerHttpTest {

    private static final String USER = ActingUser.HEADER;

    @Autowired
    MockMvc mvc;
    @MockitoBean
    OrganizationService organization;
    @MockitoBean
    IncidentService incidents;
    @MockitoBean
    AuditService audit;
    @MockitoBean
    NotificationService notifications;
    @MockitoBean
    EscalationService escalations;

    private final UUID incidentId = UUID.randomUUID();

    /**
     * Like the real organization service: ids pass through, the seeded usernames are known, Bob and Dan may act.
     */
    @BeforeEach
    void knownUsers() {
        Map<String, UUID> usernames = Map.of("bob", BOB.id(), "dan", DAN.id());
        when(organization.findUserId(anyString())).thenAnswer(invocation -> {
            String user = invocation.getArgument(0);
            try {
                return Optional.of(UUID.fromString(user));
            } catch (IllegalArgumentException e) {
                return Optional.ofNullable(usernames.get(user));
            }
        });
        when(organization.getActiveActor(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            if (id.equals(BOB.id())) {
                return BOB;
            }
            if (id.equals(DAN.id())) {
                return DAN;
            }
            throw new UnauthenticatedException("unknown");
        });
        when(organization.getTeam(any())).thenAnswer(invocation ->
                new TeamView(invocation.getArgument(0), "Database", false, List.of()));
    }

    private IncidentView incident(IncidentStatus status, Severity severity) {
        return new IncidentView(incidentId, "DB down", "timeouts", DATABASE_CATEGORY, "Database", DATABASE, BOB.id(),
                severity, status, NOW, NOW, null, null, null, null, null, List.of());
    }

    @Test
    void listsAreReachable() throws Exception {
        when(organization.listTeams()).thenReturn(List.of());
        when(incidents.listAll()).thenReturn(List.of());
        when(incidents.listByTeam(DATABASE)).thenReturn(List.of());
        when(incidents.listReportedBy(BOB.id())).thenReturn(List.of());

        mvc.perform(get("/teams").header(USER, "bob")).andExpect(status().isOk());
        mvc.perform(get("/incidents").header(USER, "bob")).andExpect(status().isOk());
        mvc.perform(get("/teams/{id}/incidents", DATABASE).header(USER, "bob")).andExpect(status().isOk());
        mvc.perform(get("/users/bob/incidents").header(USER, "dan")).andExpect(status().isOk());
        mvc.perform(get("/users/nobody/incidents").header(USER, "dan")).andExpect(status().isNotFound());

        verify(incidents).listReportedBy(BOB.id());
    }

    @Test
    void createReturns201() throws Exception {
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        when(incidents.create(any(), any(), any(), any())).thenReturn(incident(IncidentStatus.OPEN, Severity.SEV2));

        mvc.perform(post("/incidents").header(USER, "bob").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "DB down", "description": "timeouts",
                                 "categoryId": "%s", "severity": "SEV2"}""".formatted(DATABASE_CATEGORY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void patchWithStatusChangesTheStatus() throws Exception {
        when(incidents.get(incidentId)).thenReturn(incident(IncidentStatus.IN_REVIEW, Severity.SEV2));
        when(incidents.changeStatus(DAN, incidentId, IncidentStatus.RESOLVED, "index rebuilt"))
                .thenReturn(new IncidentChange(incident(IncidentStatus.IN_REVIEW, Severity.SEV2),
                        incident(IncidentStatus.RESOLVED, Severity.SEV2)));

        mvc.perform(patch("/incidents/{id}", incidentId).header(USER, "dan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"reason\": \"index rebuilt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void patchWithHigherSeverityEscalates() throws Exception {
        when(incidents.get(incidentId)).thenReturn(incident(IncidentStatus.IN_PROGRESS, Severity.SEV2));
        when(incidents.escalate(DAN, incidentId, Severity.SEV1, null))
                .thenReturn(new IncidentChange(incident(IncidentStatus.IN_PROGRESS, Severity.SEV2),
                        incident(IncidentStatus.IN_PROGRESS, Severity.SEV1)));

        mvc.perform(patch("/incidents/{id}", incidentId).header(USER, "dan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\": \"SEV1\", \"reason\": \"lag\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("SEV1"));

        verify(escalations).recordAndNotify(any(), any());
    }

    @Test
    void patchValidationErrorsAre400() throws Exception {
        mvc.perform(patch("/incidents/{id}", incidentId).header(USER, "dan").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/incidents/{id}", incidentId).header(USER, "dan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"severity\": \"SEV1\", \"reason\": \" \"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/incidents/{id}", incidentId).header(USER, "dan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetTeamId\": \"%s\"}".formatted(PLATFORM))).andExpect(status().isBadRequest());

        verifyNoInteractions(incidents, escalations);
    }

    @Test
    void lowerSeverityWithHandOverIs409() throws Exception {
        when(incidents.get(incidentId)).thenReturn(incident(IncidentStatus.IN_PROGRESS, Severity.SEV1));

        mvc.perform(patch("/incidents/{id}", incidentId).header(USER, "dan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\": \"SEV3\", \"targetTeamId\": \"%s\", \"reason\": \"x\"}"
                                .formatted(PLATFORM)))
                .andExpect(status().isConflict());

        verify(incidents, never()).deEscalate(any(), any(), any());
    }

    @Test
    void commentAndHistoryAreReachable() throws Exception {
        when(incidents.addComment(BOB, incidentId, "more")).thenReturn(
                new CommentView(UUID.randomUUID(), incidentId, BOB.id(), "more", NOW));
        when(incidents.get(incidentId)).thenReturn(incident(IncidentStatus.OPEN, Severity.SEV2));
        when(audit.getIncidentTimeline(incidentId)).thenReturn(List.of());

        mvc.perform(post("/incidents/{id}/comments", incidentId).header(USER, "bob")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"more\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/incidents/{id}", incidentId).header(USER, "bob")).andExpect(status().isOk());
        mvc.perform(get("/incidents/{id}/history", incidentId).header(USER, "bob")).andExpect(status().isOk());
    }

    @Test
    void callerErrorsMapToStatusCodes() throws Exception {
        mvc.perform(get("/incidents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/incidents").header(USER, "nobody"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Unknown user nobody"));
        mvc.perform(get("/incidents").header(USER, UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/incidents/not-a-uuid").header(USER, "bob")).andExpect(status().isBadRequest());

        when(incidents.get(incidentId)).thenReturn(incident(IncidentStatus.OPEN, Severity.SEV2));
        when(incidents.changeStatus(BOB, incidentId, IncidentStatus.CANCELLED, "x"))
                .thenThrow(new ForbiddenException("not your team"));
        mvc.perform(patch("/incidents/{id}", incidentId).header(USER, "bob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"CANCELLED\", \"reason\": \"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheListedEndpointsExist() throws Exception {
        mvc.perform(get("/categories").header(USER, "bob")).andExpect(status().isNotFound());
        mvc.perform(post("/incidents/{id}/acknowledge", incidentId).header(USER, "dan"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/incidents/{id}/status", incidentId).header(USER, "dan")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/admin/notifications/dead-lettered").header(USER, "bob")).andExpect(status().isNotFound());
    }
}
