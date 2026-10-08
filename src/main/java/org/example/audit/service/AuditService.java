package org.example.audit.service;

import java.util.List;
import java.util.UUID;

import org.example.audit.model.AuditEntryView;
import org.example.audit.model.AuditRecord;

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
