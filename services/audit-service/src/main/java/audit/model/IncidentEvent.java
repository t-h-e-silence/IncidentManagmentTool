package audit.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An incident event as audit-service reads it from RabbitMQ: only the envelope fields it needs; unknown fields are
 * ignored (tolerant reader, ADR D4). {@code data} stays a map, so new fields never break this service.
 *
 * @param type event type without urgency, e.g. {@code incident.escalated}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentEvent(UUID eventId, String type, Instant occurredAt, String correlationId, ActorRef actor,
                            UUID incidentId, Map<String, Object> data) {

    public static final String CREATED = "incident.created";
    public static final String DETAILS_UPDATED = "incident.details-updated";
    public static final String STATUS_CHANGED = "incident.status-changed";
    public static final String ESCALATED = "incident.escalated";
    public static final String DE_ESCALATED = "incident.de-escalated";
    public static final String COMMENT_ADDED = "incident.comment-added";

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
