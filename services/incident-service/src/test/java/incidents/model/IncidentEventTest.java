package incidents.model;

import static incidents.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class IncidentEventTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static IncidentView incident(Severity severity) {
        return new Incident(UUID.randomUUID(), "Checkout is slow", "Pages take 10 s", DATABASE_CATEGORY, "Database",
                DATABASE, BOB.id(), severity, NOW).toView();
    }

    @Test
    void sev1AndSev2AreUrgent() {
        assertThat(IncidentEvent.created(incident(Severity.SEV1), BOB, "c", NOW).routingKey())
                .isEqualTo("incident.created.urgent");
        assertThat(IncidentEvent.created(incident(Severity.SEV2), BOB, "c", NOW).routingKey())
                .isEqualTo("incident.created.urgent");
        assertThat(IncidentEvent.created(incident(Severity.SEV3), BOB, "c", NOW).routingKey())
                .isEqualTo("incident.created.normal");
        assertThat(IncidentEvent.created(incident(Severity.SEV4), BOB, "c", NOW).routingKey())
                .isEqualTo("incident.created.normal");
    }

    @Test
    void bodyIsTheSelfContainedEnvelope() throws Exception {
        IncidentView incident = incident(Severity.SEV2);

        JsonNode body = json.readTree(json.writeValueAsString(
                IncidentEvent.created(incident, BOB, "corr-1", NOW)));

        assertThat(body.path("type").asText()).isEqualTo("incident.created");
        assertThat(body.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("occurredAt").asText()).isEqualTo("2026-10-02T10:00:00Z");
        assertThat(body.path("correlationId").asText()).isEqualTo("corr-1");
        assertThat(body.path("actor").path("name").asText()).isEqualTo("Bob Reporter");
        assertThat(body.path("incidentId").asText()).isEqualTo(incident.id().toString());
        assertThat(body.path("data").path("incident").path("teamId").asText()).isEqualTo(DATABASE.toString());
        assertThat(body.path("data").path("incident").path("severity").asText()).isEqualTo("SEV2");
        assertThat(body.path("data").path("description").asText()).isEqualTo("Pages take 10 s");
        assertThat(body.has("routingKey")).isFalse();
    }

    @Test
    void blankNoteIsLeftEmpty() {
        IncidentView before = incident(Severity.SEV3);
        IncidentChange change = new IncidentChange(before, before);

        assertThat(IncidentEvent.statusChanged(change, "  ", DAN, "c", NOW).data()).containsEntry("note", null);
        assertThat(IncidentEvent.statusChanged(change, " fixed ", DAN, "c", NOW).data())
                .containsEntry("note", "fixed");
    }
}
