package org.example.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.TestData.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.example.common.exception.BusinessRuleException;
import org.example.common.exception.ForbiddenException;
import org.example.common.exception.NotFoundException;
import org.example.common.model.Severity;
import org.example.incidents.model.Incident;
import org.example.incidents.model.IncidentChange;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;
import org.example.incidents.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class IncidentServiceImplTest {

    @Mock
    IncidentRepository repository;

    private IncidentServiceImpl service;
    private Incident incident;

    @BeforeEach
    void setUp() {
        service = new IncidentServiceImpl(repository, CLOCK);
        incident = new Incident(UUID.randomUUID(), "DB down", "", DATABASE_CATEGORY, "Database", DATABASE, BOB.id(),
                Severity.SEV2, NOW);
    }

    private void stored() {
        when(repository.findById(incident.getId())).thenReturn(Optional.of(incident));
    }

    private void saves() {
        when(repository.saveAndFlush(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsOpenIncidentForTheTeam() {
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IncidentView view = service.create(BOB,
                new ReportIncidentCommand("Payments down", "card errors", PAYMENTS, Severity.SEV2), "Payments", PLATFORM);

        assertThat(view.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(view.teamId()).isEqualTo(PLATFORM);
        assertThat(view.reporterId()).isEqualTo(BOB.id());
        assertThat(view.createdAt()).isEqualTo(NOW);
    }

    @Test
    void reporterMayNotSetSev1() {
        assertThatThrownBy(() -> service.create(BOB,
                new ReportIncidentCommand("Everything down", null, PAYMENTS, Severity.SEV1), "Payments", PLATFORM))
                .isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void unknownIncidentIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void teamMemberAcknowledgesAndGetsBeforeAndAfter() {
        stored();
        saves();

        IncidentChange change = service.acknowledge(DAN, incident.getId());

        assertThat(change.before().status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(change.after().status()).isEqualTo(IncidentStatus.IN_PROGRESS);
    }

    @Test
    void otherTeamOrReporterMayNotChangeIt() {
        stored();

        assertThatThrownBy(() -> service.acknowledge(CAROL, incident.getId())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.resolve(BOB, incident.getId(), "done")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.changeSeverity(ADA, incident.getId(), Severity.SEV1))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reporterAndTeamMayCommentOthersMayNot() {
        stored();
        saves();

        assertThat(service.addComment(BOB, incident.getId(), "more details").authorId()).isEqualTo(BOB.id());
        assertThat(service.addComment(DAN, incident.getId(), "looking").authorId()).isEqualTo(DAN.id());
        assertThatThrownBy(() -> service.addComment(CAROL, incident.getId(), "hi"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminMayReassignOtherUsersMayNot() {
        stored();
        saves();

        assertThatThrownBy(() -> service.reassign(BOB, incident.getId(), PLATFORM))
                .isInstanceOf(ForbiddenException.class);

        IncidentChange change = service.reassign(ADA, incident.getId(), PLATFORM);

        assertThat(change.before().teamId()).isEqualTo(DATABASE);
        assertThat(change.after().teamId()).isEqualTo(PLATFORM);
    }

    @Test
    void concurrentChangeIsAClearError() {
        stored();
        when(repository.saveAndFlush(any(Incident.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Incident.class, incident.getId()));

        assertThatThrownBy(() -> service.changeSeverity(DAN, incident.getId(), Severity.SEV3))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("changed concurrently");
    }
}
