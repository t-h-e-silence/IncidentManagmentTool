package modules.audit.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What to append to the audit log.
 *
 * @param incidentId the incident the action belongs to, so it shows on its timeline; null if none
 * @param details    what changed, e.g. {@code from}/{@code to}; ids, not personal data
 */
public record AuditRecord(UUID actorId, AuditAction action, AuditEntityType entityType, UUID entityId,
                          UUID incidentId, Map<String, String> details, String correlationId) {

    public AuditRecord {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(correlationId, "correlationId");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
