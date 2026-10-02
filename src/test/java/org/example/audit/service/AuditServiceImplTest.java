package org.example.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.TestData.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.example.audit.model.AuditAction;
import org.example.audit.model.AuditEntityType;
import org.example.audit.model.AuditEntry;
import org.example.audit.model.AuditEntryView;
import org.example.audit.model.AuditRecord;
import org.example.audit.repository.AuditEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock
    AuditEntryRepository repository;

    @Test
    void recordsEverythingWithTheCurrentTime() {
        AuditServiceImpl service = new AuditServiceImpl(repository, CLOCK);
        UUID incidentId = UUID.randomUUID();

        service.record(new AuditRecord(BOB.id(), AuditAction.INCIDENT_CREATED, AuditEntityType.INCIDENT, incidentId,
                incidentId, Map.of("severity", "SEV2"), "corr-1"));

        ArgumentCaptor<AuditEntry> saved = ArgumentCaptor.forClass(AuditEntry.class);
        verify(repository).save(saved.capture());
        AuditEntryView entry = saved.getValue().toView();
        assertThat(entry.actorId()).isEqualTo(BOB.id());
        assertThat(entry.action()).isEqualTo(AuditAction.INCIDENT_CREATED);
        assertThat(entry.incidentId()).isEqualTo(incidentId);
        assertThat(entry.details()).containsEntry("severity", "SEV2");
        assertThat(entry.correlationId()).isEqualTo("corr-1");
        assertThat(entry.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void timelineComesFromTheRepositoryInOrder() {
        AuditServiceImpl service = new AuditServiceImpl(repository, CLOCK);
        UUID incidentId = UUID.randomUUID();
        AuditEntry first = new AuditEntry(new AuditRecord(BOB.id(), AuditAction.INCIDENT_CREATED,
                AuditEntityType.INCIDENT, incidentId, incidentId, null, "c1"), NOW);
        AuditEntry second = new AuditEntry(new AuditRecord(DAN.id(), AuditAction.STATUS_CHANGED,
                AuditEntityType.INCIDENT, incidentId, incidentId, null, "c2"), NOW.plusSeconds(60));
        when(repository.findByIncidentIdOrderByOccurredAtAsc(incidentId)).thenReturn(List.of(first, second));

        assertThat(service.getIncidentTimeline(incidentId)).extracting(AuditEntryView::action)
                .containsExactly(AuditAction.INCIDENT_CREATED, AuditAction.STATUS_CHANGED);
    }
}
