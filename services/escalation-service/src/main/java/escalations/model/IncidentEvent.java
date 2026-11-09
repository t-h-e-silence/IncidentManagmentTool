package escalations.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An {@code incident.escalated} or {@code incident.de-escalated} event as escalation-service reads it from RabbitMQ:
 * only the envelope fields it needs; unknown fields are ignored (tolerant reader, ADR D4). {@code data} stays a map,
 * so new fields never break this service.
 *
 * @param type event type without urgency
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentEvent(UUID eventId, String type, Instant occurredAt, String correlationId, ActorRef actor,
                            UUID incidentId, Map<String, Object> data) {

    public static final String ESCALATED = "incident.escalated";
    public static final String DE_ESCALATED = "incident.de-escalated";

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
     * A field of {@code data.incident}, the incident after the change, as text, or null if it is missing.
     */
    public String incidentText(String field) {
        Object incident = data == null ? null : data.get("incident");
        Object value = incident instanceof Map<?, ?> map ? map.get(field) : null;
        return value == null ? null : value.toString();
    }
}
