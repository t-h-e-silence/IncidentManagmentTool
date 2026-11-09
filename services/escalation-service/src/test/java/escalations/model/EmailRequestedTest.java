package escalations.model;

import static escalations.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

/**
 * The command is notification-service's contract (ADR D14): these are the fields it reads.
 */
class EmailRequestedTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static EscalationRecord record(Severity from, Severity to) {
        return new EscalationRecord(UUID.randomUUID(), "DB down", DAN.userId(), "Dan Dba", "lag", from, to,
                DATABASE, "Database", DATABASE, "Database");
    }

    @Test
    void bodyIsTheEnvelopeNotificationServiceReads() throws Exception {
        EscalationRecord record = record(Severity.SEV3, Severity.SEV1);

        JsonNode body = json.readTree(json.writeValueAsString(
                EmailRequested.of(record, "corr-1", BOB, "[SEV1] Your incident was escalated: DB down", "text", NOW)));

        assertThat(body.path("type").asText()).isEqualTo("notification.email-requested");
        assertThat(body.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("correlationId").asText()).isEqualTo("corr-1");
        assertThat(body.path("incidentId").asText()).isEqualTo(record.incidentId().toString());
        assertThat(body.path("actor").path("name").asText()).isEqualTo("Dan Dba");
        JsonNode data = body.path("data");
        assertThat(data.path("reason").asText()).isEqualTo("INCIDENT_ESCALATED");
        assertThat(data.path("recipient").path("userId").asText()).isEqualTo(BOB.userId().toString());
        assertThat(data.path("recipient").path("email").asText()).isEqualTo("bob@example.com");
        assertThat(data.path("subject").asText()).isEqualTo("[SEV1] Your incident was escalated: DB down");
        assertThat(data.path("body").asText()).isEqualTo("text");
        assertThat(body.has("routingKey")).isFalse();
    }

    @Test
    void urgencyFollowsTheNewSeverity() {
        assertThat(EmailRequested.of(record(Severity.SEV3, Severity.SEV2), "c", BOB, "s", "b", NOW).routingKey())
                .isEqualTo("notification.email-requested.urgent");
        assertThat(EmailRequested.of(record(Severity.SEV1, Severity.SEV3), "c", BOB, "s", "b", NOW).routingKey())
                .isEqualTo("notification.email-requested.normal");
        assertThat(EmailRequested.of(record(Severity.SEV1, Severity.SEV3), "c", BOB, "s", "b", NOW).data())
                .containsEntry("reason", "INCIDENT_DEESCALATED");
    }
}
