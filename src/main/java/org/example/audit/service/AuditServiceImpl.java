package org.example.audit.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.example.audit.model.AuditEntry;
import org.example.audit.model.AuditEntryView;
import org.example.audit.model.AuditRecord;
import org.example.audit.repository.AuditEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuditServiceImpl implements AuditService {

    private final AuditEntryRepository entries;
    private final Clock clock;

    public AuditServiceImpl(AuditEntryRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    @Override
    public void record(AuditRecord record) {
        entries.save(new AuditEntry(record, clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntryView> getIncidentTimeline(UUID incidentId) {
        return entries.findByIncidentIdOrderByOccurredAtAsc(incidentId).stream().map(AuditEntry::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntryView> getActionsByUser(UUID userId) {
        return entries.findByActorIdOrderByOccurredAtAsc(userId).stream().map(AuditEntry::toView).toList();
    }
}
