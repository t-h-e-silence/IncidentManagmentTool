package modules.audit.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import modules.audit.model.AuditEntry;
import modules.audit.model.AuditEntryView;
import modules.audit.model.AuditRecord;
import modules.audit.repository.AuditEntryRepository;
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

}
