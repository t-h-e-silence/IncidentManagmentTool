package modules.audit.repository;

import java.util.List;
import java.util.UUID;

import modules.audit.model.AuditEntry;
import org.springframework.data.repository.Repository;

/**
 * Append and read only: no update or delete methods.
 */
public interface AuditEntryRepository extends Repository<AuditEntry, UUID> {

    AuditEntry save(AuditEntry entry);

    List<AuditEntry> findByIncidentIdOrderByOccurredAtAsc(UUID incidentId);
}
