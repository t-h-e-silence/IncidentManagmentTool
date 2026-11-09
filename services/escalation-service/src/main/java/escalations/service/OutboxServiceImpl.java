package escalations.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import escalations.model.EmailRequested;
import escalations.model.OutboxMessage;
import escalations.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OutboxServiceImpl implements OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxServiceImpl.class);

    static final String APP_ID = "escalation-service";
    static final String VERSION_HEADER = "x-event-version";

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final Clock clock;
    private final int batchSize;
    private final long confirmTimeoutMillis;

    public OutboxServiceImpl(OutboxRepository outbox, RabbitTemplate rabbit, ObjectMapper json, Clock clock,
                             @Value("${outbox.relay.batch-size}") int batchSize,
                             @Value("${outbox.relay.confirm-timeout}") Duration confirmTimeout) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.json = json;
        this.clock = clock;
        this.batchSize = batchSize;
        this.confirmTimeoutMillis = confirmTimeout.toMillis();
    }

    @Override
    public void publish(EmailRequested command) {
        try {
            outbox.save(new OutboxMessage(command, json.writeValueAsString(command)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Command " + command.type() + " cannot be written as JSON", e);
        }
    }

    /**
     * Sends the whole batch first and then waits for the confirms, so one slow confirm does not hold up the rest.
     * A crash after sending and before commit publishes those commands again; notification-service skips duplicates
     * (ADR D4).
     */
    @Override
    public int relayPending() {
        List<OutboxMessage> pending = outbox.lockUnpublished(batchSize);
        Map<OutboxMessage, CorrelationData> sent = new LinkedHashMap<>();
        for (OutboxMessage message : pending) {
            CorrelationData confirm = new CorrelationData(message.getEventId().toString());
            rabbit.send(message.getExchange(), message.getRoutingKey(), toAmqp(message), confirm);
            sent.put(message, confirm);
        }
        int confirmed = 0;
        Instant now = clock.instant();
        for (Map.Entry<OutboxMessage, CorrelationData> entry : sent.entrySet()) {
            OutboxMessage message = entry.getKey();
            try {
                CorrelationData.Confirm confirm = entry.getValue().getFuture()
                        .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
                if (confirm.isAck()) {
                    message.markPublished(now);
                    confirmed++;
                } else {
                    log.warn("RabbitMQ refused command {} ({}): {}; retrying on the next run", message.getEventId(),
                            message.getRoutingKey(), confirm.getReason());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("No confirm for command {} ({}): {}; retrying on the next run", message.getEventId(),
                        message.getRoutingKey(), e.toString());
            }
        }
        return confirmed;
    }

    @Override
    public int deletePublishedBefore(Instant before) {
        return outbox.deletePublishedBefore(before);
    }

    /**
     * The JSON envelope as body; the AMQP properties mirror it for tooling, routing never uses them (ADR D14).
     * No {@code __TypeId__} header: consumers map the body to their own classes.
     */
    private static Message toAmqp(OutboxMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(message.getEventId().toString());
        properties.setType(message.getType());
        properties.setCorrelationId(message.getCorrelationId());
        properties.setTimestamp(Date.from(message.getOccurredAt()));
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setAppId(APP_ID);
        properties.setHeader(VERSION_HEADER, EmailRequested.VERSION);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(message.getPayload().getBytes(StandardCharsets.UTF_8), properties);
    }
}
