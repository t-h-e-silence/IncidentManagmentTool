package escalations.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@value #TYPE} command: one ready-made escalation email for notification-service, published to the
 * {@value #EXCHANGE} direct exchange through the outbox (ADR D2, D14). This record is the JSON body, in the same
 * envelope as the incident events; notification-service stores and sends the email as it is.
 *
 * <p>{@code data} = {@code reason} ({@code INCIDENT_ESCALATED} / {@code INCIDENT_DEESCALATED}), {@code recipient}
 * ({@code userId}, {@code name}, {@code email}), {@code subject}, {@code body}. Fields are only ever added.
 *
 * @param correlationId the escalating request's, so the emails can be traced back to it
 */
public record EmailRequested(UUID eventId, String type, int version, Instant occurredAt, String correlationId,
                             ActorRef actor, UUID incidentId, Map<String, Object> data) {

    public static final String EXCHANGE = "notifications.commands";
    public static final String TYPE = "notification.email-requested";
    public static final int VERSION = 1;

    public record ActorRef(UUID id, String name) {
    }

    /**
     * One email of an escalation or de-escalation.
     */
    public static EmailRequested of(EscalationRecord record, String correlationId, Recipient recipient,
                                    String subject, String body, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", record.direction() == EscalationDirection.UP ? "INCIDENT_ESCALATED" : "INCIDENT_DEESCALATED");
        data.put("recipient", recipient);
        data.put("subject", subject);
        data.put("body", body);
        data.put("severity", record.toSeverity());
        return new EmailRequested(UUID.randomUUID(), TYPE, VERSION, now, correlationId,
                new ActorRef(record.actorId(), record.actorName()), record.incidentId(), data);
    }

    /**
     * {@code notification.email-requested.<urgent|normal>}: urgent when the incident is SEV1 or SEV2 after the change
     * (ADR D8, D14).
     */
    public String routingKey() {
        Severity severity = (Severity) data.get("severity");
        return TYPE + (severity.isHigherThan(Severity.SEV3) ? ".urgent" : ".normal");
    }
}
