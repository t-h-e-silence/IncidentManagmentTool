package incidents.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An event about an incident, published to the {@value #EXCHANGE} topic exchange through the outbox (ADR D4, D14).
 * This record is the JSON body: a self-contained envelope, so a message in a dead-letter queue can be read alone.
 *
 * <p>{@code data.incident} is the incident after the change ({@link Snapshot}); the other {@code data} fields
 * depend on the type. Fields are only ever added, never renamed or removed.
 *
 * @param type event type without urgency, e.g. {@code incident.escalated}
 */
public record IncidentEvent(UUID eventId, String type, int version, Instant occurredAt, String correlationId,
                            ActorRef actor, UUID incidentId, Map<String, Object> data) {

    public static final String EXCHANGE = "incidents.events";

    public static final String CREATED = "incident.created";
    public static final String DETAILS_UPDATED = "incident.details-updated";
    public static final String STATUS_CHANGED = "incident.status-changed";
    public static final String ESCALATED = "incident.escalated";
    public static final String DE_ESCALATED = "incident.de-escalated";
    public static final String COMMENT_ADDED = "incident.comment-added";

    public static final int VERSION = 1;

    /**
     * Who made the change.
     */
    public record ActorRef(UUID id, String name) {
    }

    /**
     * The incident after the change, so consumers need not call back into incident-service.
     */
    public record Snapshot(UUID id, String title, String categoryName, UUID teamId, UUID reporterId,
                           Severity severity, IncidentStatus status) {
    }

    /**
     * {@code <type>.<urgent|normal>}: urgent when the incident is SEV1 or SEV2 after the change (ADR D8, D14).
     */
    public String routingKey() {
        Severity severity = ((Snapshot) data.get("incident")).severity();
        return type + (severity.isHigherThan(Severity.SEV3) ? ".urgent" : ".normal");
    }

    public static IncidentEvent created(IncidentView incident, Actor actor, String correlationId, Instant now) {
        Map<String, Object> data = data(incident);
        data.put("description", incident.description());
        data.put("categoryId", incident.categoryId());
        return of(CREATED, incident, actor, correlationId, now, data);
    }

    public static IncidentEvent detailsUpdated(IncidentChange change, Actor actor, String correlationId,
                                               Instant now) {
        Map<String, Object> data = data(change.after());
        data.put("fromTitle", change.before().title());
        data.put("toTitle", change.after().title());
        data.put("descriptionChanged", !change.before().description().equals(change.after().description()));
        return of(DETAILS_UPDATED, change.after(), actor, correlationId, now, data);
    }

    /**
     * @param note the reason given, if any (required to resolve, cancel and reopen)
     */
    public static IncidentEvent statusChanged(IncidentChange change, String note, Actor actor, String correlationId,
                                              Instant now) {
        Map<String, Object> data = data(change.after());
        data.put("fromStatus", change.before().status());
        data.put("toStatus", change.after().status());
        data.put("note", note == null || note.isBlank() ? null : note.strip());
        return of(STATUS_CHANGED, change.after(), actor, correlationId, now, data);
    }

    /**
     * Escalation (severity up, maybe handed over) or de-escalation (severity down, same team).
     */
    public static IncidentEvent severityChanged(boolean escalation, IncidentChange change, String reason,
                                                Actor actor, String correlationId, Instant now) {
        Map<String, Object> data = data(change.after());
        data.put("fromSeverity", change.before().severity());
        data.put("toSeverity", change.after().severity());
        data.put("fromTeamId", change.before().teamId());
        data.put("toTeamId", change.after().teamId());
        data.put("reason", reason);
        return of(escalation ? ESCALATED : DE_ESCALATED, change.after(), actor, correlationId, now, data);
    }

    public static IncidentEvent commentAdded(IncidentView incident, CommentView comment, Actor actor,
                                             String correlationId, Instant now) {
        Map<String, Object> data = data(incident);
        data.put("commentId", comment.id());
        data.put("text", comment.text());
        return of(COMMENT_ADDED, incident, actor, correlationId, now, data);
    }

    private static Map<String, Object> data(IncidentView incident) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incident", new Snapshot(incident.id(), incident.title(), incident.categoryName(), incident.teamId(),
                incident.reporterId(), incident.severity(), incident.status()));
        return data;
    }

    private static IncidentEvent of(String type, IncidentView incident, Actor actor, String correlationId,
                                    Instant now, Map<String, Object> data) {
        return new IncidentEvent(UUID.randomUUID(), type, VERSION, now, correlationId,
                new ActorRef(actor.id(), actor.name()), incident.id(), data);
    }
}
