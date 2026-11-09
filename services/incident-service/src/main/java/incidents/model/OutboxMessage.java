package incidents.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An event waiting to be published to RabbitMQ (transactional outbox, ADR D4). Stored in the same transaction as
 * the incident change; the relay publishes it and sets {@code publishedAt} once RabbitMQ confirms it.
 */
@Entity
@Table(name = "outbox", schema = "incidents")
public class OutboxMessage {

    /** Publish order. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 100)
    private String exchange;

    @Column(name = "routing_key", nullable = false, length = 200)
    private String routingKey;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(name = "correlation_id", nullable = false, length = 100)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** The JSON envelope ({@link IncidentEvent}). */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxMessage() {
        // for JPA
    }

    public OutboxMessage(IncidentEvent event, String payload) {
        this.eventId = event.eventId();
        this.exchange = IncidentEvent.EXCHANGE;
        this.routingKey = event.routingKey();
        this.type = event.type();
        this.correlationId = event.correlationId();
        this.occurredAt = event.occurredAt();
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public void markPublished(Instant now) {
        publishedAt = now;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getType() {
        return type;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
