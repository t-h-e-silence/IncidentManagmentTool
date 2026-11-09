package escalations.service;

import static escalations.TestData.*;
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

import escalations.model.EmailRequested;
import escalations.model.EscalationRecord;
import escalations.model.OutboxMessage;
import escalations.model.Severity;
import escalations.repository.OutboxRepository;
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

    private static EmailRequested event(Severity severity) {
        Severity from = severity == Severity.SEV4 ? Severity.SEV3 : Severity.SEV4;
        return EmailRequested.of(new EscalationRecord(UUID.randomUUID(), "DB down", DAN.userId(), "Dan Dba", "lag",
                from, severity, DATABASE, "Database", DATABASE, "Database"), "corr-1", BOB, "subject", "body", NOW);
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
        EmailRequested event = event(Severity.SEV1);

        outbox.publish(event);

        ArgumentCaptor<OutboxMessage> saved = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRoutingKey()).isEqualTo("notification.email-requested.urgent");
        assertThat(saved.getValue().getExchange()).isEqualTo("notifications.commands");
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
        EmailRequested event = event(Severity.SEV2);
        OutboxMessage message = new OutboxMessage(event, "{\"type\":\"notification.email-requested\"}");
        when(repository.lockUnpublished(100)).thenReturn(List.of(message));
        confirms(true);

        outbox.relayPending();

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("notifications.commands"), eq("notification.email-requested.urgent"), sent.capture(),
                any(CorrelationData.class));
        var properties = sent.getValue().getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(properties.getType()).isEqualTo("notification.email-requested");
        assertThat(properties.getCorrelationId()).isEqualTo("corr-1");
        assertThat(properties.getContentType()).isEqualTo("application/json");
        assertThat(properties.getAppId()).isEqualTo("escalation-service");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((Integer) properties.getHeader("x-event-version")).isEqualTo(1);
        assertThat(properties.getHeaders()).doesNotContainKey("__TypeId__");
        assertThat(new String(sent.getValue().getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"type\":\"notification.email-requested\"}");
    }
}
