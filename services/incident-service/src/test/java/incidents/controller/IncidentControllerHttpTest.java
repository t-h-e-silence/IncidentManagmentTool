package incidents.controller;

import static incidents.TestData.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import incidents.model.CategoryRouting;
import incidents.model.Incident;
import incidents.model.IncidentSummary;
import incidents.model.IncidentView;
import incidents.model.ReportIncidentCommand;
import incidents.model.Severity;
import incidents.service.IdempotencyService;
import incidents.service.IncidentService;
import incidents.service.OrganizationClient;
import incidents.service.OutboxService;
import incidents.web.ActingUser;
import incidents.web.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP side of {@link IncidentController}: routes, the {@value ActingUser#HEADER} and
 * {@value IdempotencyService#HEADER} headers, bodies and status codes. What each action does is covered by
 * {@link IncidentControllerTest}.
 */
@WebMvcTest(IncidentController.class)
class IncidentControllerHttpTest {

    private static final String USER = ActingUser.HEADER;

    @Autowired
    MockMvc mvc;
    @MockitoBean
    OrganizationClient organization;
    @MockitoBean
    IncidentService incidents;
    @MockitoBean
    OutboxService outbox;
    @MockitoBean
    IdempotencyService idempotency;

    private final IncidentView incident = new Incident(UUID.randomUUID(), "Checkout is slow", "", DATABASE_CATEGORY,
            "Database", DATABASE, BOB.id(), Severity.SEV2, NOW).toView();

    /**
     * Like the real organization client: ids pass through, the demo usernames are known, Bob and Dan may act.
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
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get())
                .when(idempotency).execute(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void createIs201WithTheIncident() throws Exception {
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        when(incidents.create(eq(BOB), any(ReportIncidentCommand.class), eq("Database"), eq(DATABASE)))
                .thenReturn(incident);

        mvc.perform(post("/incidents").header(USER, "bob").header(IdempotencyService.HEADER, "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Checkout is slow", "categoryId": "%s", "severity": "SEV2"}"""
                                .formatted(DATABASE_CATEGORY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.teamId").value(DATABASE.toString()));

        verify(idempotency).execute(eq("key-1"), eq(BOB.id()), eq("POST /incidents"), any(),
                eq(IncidentView.class), any());
    }

    @Test
    void emptyUpdateIs400() throws Exception {
        mvc.perform(patch("/incidents/{id}", incident.id()).header(USER, "dan")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsAndViewNeedAKnownCaller() throws Exception {
        when(incidents.listReportedBy(BOB.id())).thenReturn(List.of(new IncidentSummary(incident.id(),
                incident.title(), incident.teamId(), incident.reporterId(), incident.severity(), incident.status(),
                incident.createdAt())));

        mvc.perform(get("/users/bob/incidents").header(USER, "dan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Checkout is slow"));
        mvc.perform(get("/users/nobody/incidents").header(USER, "dan"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/incidents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void organizationDownIs503() throws Exception {
        when(organization.getActiveActor(DAN.id()))
                .thenThrow(new ServiceUnavailableException("organization-service is unavailable, retry", null));

        mvc.perform(get("/incidents/{id}", incident.id()).header(USER, "dan"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("organization-service is unavailable, retry"));
    }
}
