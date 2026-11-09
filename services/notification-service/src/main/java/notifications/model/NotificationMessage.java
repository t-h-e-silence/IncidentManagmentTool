package notifications.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A message as notification-service reads it from RabbitMQ: an incident event of incident-service, or a
 * {@value #EMAIL_REQUESTED} command of escalation-service. Both use the same JSON envelope (ADR D14); only the fields
 * this service needs are read, unknown ones are ignored (tolerant reader, ADR D4). {@code data} stays a map, so new
 * fields never break this service.
 *
 * <p>{@value #EMAIL_REQUESTED}: one ready-made email, {@code data} = {@code reason} (a {@link NotificationReason}),
 * {@code recipient} ({@code userId}, {@code name}, {@code email}), {@code subject}, {@code body}.
 *
 * @param eventId event or command id; repeats are skipped by it
 * @param type    e.g. {@code incident.created}, without urgency
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationMessage(UUID eventId, String type, Instant occurredAt, String correlationId,
                                  ActorRef actor, UUID incidentId, Map<String, Object> data) {

    public static final String INCIDENT_CREATED = "incident.created";
    public static final String INCIDENT_STATUS_CHANGED = "incident.status-changed";
    public static final String EMAIL_REQUESTED = "notification.email-requested";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActorRef(UUID id, String name) {
    }

    /**
     * A {@code data} field as text, or null if it is missing.
     */
    public String text(String field) {
        Object value = data == null ? null : data.get(field);
        return value == null ? null : value.toString();
    }

    /**
     * A text field of a {@code data} object, e.g. {@code nested("incident", "title")}, or null if it is missing.
     */
    public String nested(String object, String field) {
        Object nested = data == null ? null : data.get(object);
        Object value = nested instanceof Map<?, ?> map ? map.get(field) : null;
        return value == null ? null : value.toString();
    }
}
