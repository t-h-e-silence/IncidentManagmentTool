package modules.audit.service;

import java.util.List;
import java.util.UUID;

import modules.audit.model.AuditEntryView;
import modules.audit.model.AuditRecord;

/**
 * Append-only record of every significant action.
 */
public interface AuditService {

    /**
     * Appends one entry, in the caller's transaction.
     */
    void record(AuditRecord record);

    /**
     * Everything that happened to an incident, oldest first.
     */
    List<AuditEntryView> getIncidentTimeline(UUID incidentId);

}
