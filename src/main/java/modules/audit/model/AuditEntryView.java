package modules.audit.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntryView(UUID id, UUID actorId, AuditAction action, AuditEntityType entityType, UUID entityId,
                             UUID incidentId, Map<String, String> details, String correlationId,
                             Instant occurredAt) {
}
