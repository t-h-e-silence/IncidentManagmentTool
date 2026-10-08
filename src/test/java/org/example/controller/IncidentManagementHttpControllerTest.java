package org.example.controller;

import static org.example.TestData.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.example.common.exception.BusinessRuleException;
import org.example.common.exception.ForbiddenException;
import org.example.common.exception.NotFoundException;
import org.example.common.exception.UnauthenticatedException;
import org.example.common.model.Severity;
import org.example.controller.model.EscalateCommand;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IncidentManagementHttpController.class)
class IncidentManagementHttpControllerTest {

    private static final String USER = IncidentManagementHttpController.USER_HEADER;

    @Autowired
    MockMvc mvc;
    @MockitoBean
    IncidentManagementController controller;

    private final UUID incidentId = UUID.randomUUID();

    /**
     * Like the real lookup: ids pass through, the seeded usernames are known.
     */
    @BeforeEach
    void knownUsers() {
        Map<String, UUID> usernames = Map.of("bob", BOB.id(), "dan", DAN.id());
        when(controller.findUserId(anyString())).thenAnswer(invocation -> {
            String user = invocation.getArgument(0);
            try {
                return Optional.of(UUID.fromString(user));
            } catch (IllegalArgumentException e) {
                return Optional.ofNullable(usernames.get(user));
            }
        });
    }

    private IncidentView incident(IncidentStatus status) {
        return new IncidentView(incidentId, "DB down", "timeouts", DATABASE_CATEGORY, "Database", DATABASE, BOB.id(),
                Severity.SEV2, status, NOW, NOW, null, null, null, null, null, List.of());
    }

    @Test
    void reportReadsTheHeaderAndBodyAndReturns201() throws Exception {
        ReportIncidentCommand command = new ReportIncidentCommand("DB down", "timeouts", DATABASE_CATEGORY,
                Severity.SEV2);
        when(controller.reportIncident(BOB.id(), command)).thenReturn(incident(IncidentStatus.OPEN));

        mvc.perform(post("/incidents").header(USER, BOB.id()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "DB down", "description": "timeouts",
                                 "categoryId": "%s", "severity": "SEV2"}""".formatted(DATABASE_CATEGORY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void statusChangeTakesStatusAndNote() throws Exception {
        when(controller.changeIncidentStatus(DAN.id(), incidentId, IncidentStatus.CANCELLED, "duplicate"))
                .thenReturn(incident(IncidentStatus.CANCELLED));

        mvc.perform(put("/incidents/{id}/status", incidentId).header(USER, DAN.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\", \"note\": \"duplicate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void escalationBodyIsTheEscalateCommand() throws Exception {
        when(controller.escalateIncident(any(), any(), any())).thenReturn(incident(IncidentStatus.OPEN));

        mvc.perform(post("/incidents/{id}/escalate", incidentId).header(USER, DAN.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\": \"SEV1\", \"targetTeamId\": \"%s\", \"reason\": \"lag\"}"
                                .formatted(PLATFORM)))
                .andExpect(status().isOk());

        verify(controller).escalateIncident(DAN.id(), incidentId, new EscalateCommand(Severity.SEV1, PLATFORM, "lag"));
    }

    @Test
    void escalationWithoutReasonIs400() throws Exception {
        mvc.perform(post("/incidents/{id}/escalate", incidentId).header(USER, DAN.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\": \"SEV1\", \"reason\": \" \"}"))
                .andExpect(status().isBadRequest());

        verify(controller, never()).escalateIncident(any(), any(), any());
    }

    @Test
    void missingUserHeaderIs401() throws Exception {
        mvc.perform(get("/incidents")).andExpect(status().isUnauthorized());

        verifyNoInteractions(controller);
    }

    @Test
    void userMayBeGivenByUsername() throws Exception {
        when(controller.listMyReportedIncidents(BOB.id())).thenReturn(List.of());

        mvc.perform(get("/incidents/mine").header(USER, "bob")).andExpect(status().isOk());

        verify(controller).listMyReportedIncidents(BOB.id());
    }

    @Test
    void unknownUsernameIs401() throws Exception {
        mvc.perform(get("/incidents/mine").header(USER, "nobody"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Unknown user nobody"));

        verify(controller, never()).listMyReportedIncidents(any());
    }

    @Test
    void usersInPathsMayBeUsernamesToo() throws Exception {
        when(controller.listIncidentsReportedBy(DAN.id(), BOB.id())).thenReturn(List.of());

        mvc.perform(get("/users/bob/incidents").header(USER, "dan")).andExpect(status().isOk());
        mvc.perform(get("/users/nobody/incidents").header(USER, "dan")).andExpect(status().isNotFound());

        verify(controller).listIncidentsReportedBy(DAN.id(), BOB.id());
    }

    @Test
    void malformedIdIs400() throws Exception {
        mvc.perform(get("/incidents/not-a-uuid").header(USER, BOB.id())).andExpect(status().isBadRequest());
    }

    @Test
    void exceptionsMapToStatusCodes() throws Exception {
        UUID stranger = UUID.randomUUID();
        when(controller.listAllIncidents(stranger)).thenThrow(new UnauthenticatedException("unknown"));
        when(controller.getIncident(BOB.id(), incidentId)).thenThrow(new NotFoundException("no such incident"));
        when(controller.acknowledgeIncident(CAROL.id(), incidentId)).thenThrow(new ForbiddenException("not yours"));
        when(controller.acknowledgeIncident(DAN.id(), incidentId)).thenThrow(new BusinessRuleException("not OPEN"));

        mvc.perform(get("/incidents").header(USER, stranger)).andExpect(status().isUnauthorized());
        mvc.perform(get("/incidents/{id}", incidentId).header(USER, BOB.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("no such incident"));
        mvc.perform(post("/incidents/{id}/acknowledge", incidentId).header(USER, CAROL.id()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/incidents/{id}/acknowledge", incidentId).header(USER, DAN.id()))
                .andExpect(status().isConflict());
    }

    @Test
    void myIncidentsIsNotMistakenForAnId() throws Exception {
        when(controller.listMyReportedIncidents(BOB.id())).thenReturn(List.of());

        mvc.perform(get("/incidents/mine").header(USER, BOB.id())).andExpect(status().isOk());

        verify(controller).listMyReportedIncidents(BOB.id());
    }
}
