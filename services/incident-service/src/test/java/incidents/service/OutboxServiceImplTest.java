package incidents.service;

import static incidents.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import incidents.model.Incident;
import incidents.model.IncidentEvent;
import incidents.model.OutboxMessage;
import incidents.model.Severity;
import incidents.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxServiceImplTest {

    @Mock
    OutboxRepository repository;
    @Mock
    RabbitTemplate rabbit;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private OutboxServiceImpl outbox;

    @BeforeEach
    void setUp() {
        outbox = new OutboxServiceImpl(repository, rabbit, json, CLOCK, 100, Duration.ofMillis(200));
    }

    private static IncidentEvent event(Severity severity) {
        return IncidentEvent.created(new Incident(UUID.randomUUID(), "Checkout is slow", "", DATABASE_CATEGORY,
                "Database", DATABASE, BOB.id(), severity, NOW).toView(), BOB, "corr-1", NOW);
    }

    /**
     * RabbitMQ answers each send with this ack or nack.
     */
    private void confirms(boolean... acks) {
        int[] call = {0};
        doAnswer(invocation -> {
            CorrelationData confirm = invocation.getArgument(3);
            confirm.getFuture().complete(new CorrelationData.Confirm(acks[call[0]++], "nack"));
            return null;
        }).when(rabbit).send(any(), any(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void publishStoresTheEnvelopeWithItsRoutingKey() throws Exception {
        IncidentEvent event = event(Severity.SEV1);

        outbox.publish(event);

        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRoutingKey()).isEqualTo("incident.created.urgent");
        assertThat(saved.getValue().getExchange()).isEqualTo("incidents.events");
        assertThat(json.readTree(saved.getValue().getPayload()).path("eventId").asText())
                .isEqualTo(event.eventId().toString());
    }

    @Test
    void relayMarksOnlyConfirmedEventsPublished() {
        OutboxMessage acked = new OutboxMessage(event(Severity.SEV3), "{}");
        OutboxMessage nacked = new OutboxMessage(event(Severity.SEV3), "{}");
        when(repository.lockUnpublished(100)).thenReturn(List.of(acked, nacked));
        confirms(true, false);

        assertThat(outbox.relayPending()).isEqualTo(1);

        assertThat(acked.getPublishedAt()).isEqualTo(NOW);
        assertThat(nacked.getPublishedAt()).isNull();
    }

    @Test
    void relayWithoutConfirmRetriesLater() {
        OutboxMessage message = new OutboxMessage(event(Severity.SEV3), "{}");
        when(repository.lockUnpublished(100)).thenReturn(List.of(message));

        assertThat(outbox.relayPending()).isZero();

        assertThat(message.getPublishedAt()).isNull();
    }

    @Test
    void amqpPropertiesMirrorTheEnvelope() {
        IncidentEvent event = event(Severity.SEV2);
        OutboxMessage message = new OutboxMessage(event, "{\"type\":\"incident.created\"}");
        when(repository.lockUnpublished(100)).thenReturn(List.of(message));
        confirms(true);

        outbox.relayPending();

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("incidents.events"), eq("incident.created.urgent"), sent.capture(),
                any(CorrelationData.class));
        var properties = sent.getValue().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(properties.getType()).isEqualTo("incident.created");
        assertThat(properties.getCorrelationId()).isEqualTo("corr-1");
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getAppId()).isEqualTo("incident-service");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((Integer) properties.getHeader("x-event-version")).isEqualTo(1);
        assertThat(properties.getHeaders()).doesNotContainKey("__TypeId__");
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"incident.created\"}");
    }
}
