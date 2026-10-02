package org.example.incidents.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.TestData.*;

import java.util.UUID;

import org.example.common.exception.BusinessRuleException;
import org.example.common.model.Severity;
import org.junit.jupiter.api.Test;

class IncidentTest {

    private final Incident incident = new Incident(UUID.randomUUID(), "DB down", null, DATABASE_CATEGORY, "Database",
            DATABASE, BOB.id(), Severity.SEV2, NOW);

    @Test
    void startsOpenWithEmptyDescription() {
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.toView().description()).isEmpty();
    }

    @Test
    void followsTheLifecycle() {
        incident.acknowledge(NOW);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(incident.toView().acknowledgedAt()).isEqualTo(NOW);

        incident.resolve(DAN.id(), " fixed index ", NOW);
        IncidentView view = incident.toView();
        assertThat(view.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(view.resolvedBy()).isEqualTo(DAN.id());
        assertThat(view.resolutionNote()).isEqualTo("fixed index");
    }

    @Test
    void openIncidentCanBeResolvedDirectly() {
        incident.resolve(DAN.id(), "duplicate", NOW);

        assertThat(incident.isResolved()).isTrue();
    }

    @Test
    void acknowledgeOnlyWhenOpen() {
        incident.acknowledge(NOW);

        assertThatThrownBy(() -> incident.acknowledge(NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resolvedIncidentIsReadOnly() {
        incident.resolve(DAN.id(), "done", NOW);

        assertThatThrownBy(() -> incident.resolve(DAN.id(), "again", NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.changeSeverity(Severity.SEV1, NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.addComment(DAN.id(), "late", NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.reassign(PLATFORM, NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resolveNeedsANote() {
        assertThatThrownBy(() -> incident.resolve(DAN.id(), " ", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void severityMustChange() {
        incident.changeSeverity(Severity.SEV1, NOW);
        assertThat(incident.getSeverity()).isEqualTo(Severity.SEV1);

        assertThatThrownBy(() -> incident.changeSeverity(Severity.SEV1, NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reassignHandsOverAndReopens() {
        incident.acknowledge(NOW);

        incident.reassign(PLATFORM, NOW);

        assertThat(incident.getTeamId()).isEqualTo(PLATFORM);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.toView().acknowledgedAt()).isNull();
        assertThatThrownBy(() -> incident.reassign(PLATFORM, NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void escalationRaisesSeverityAndMayHandOver() {
        incident.acknowledge(NOW);

        incident.escalate(Severity.SEV1, PLATFORM, NOW);

        assertThat(incident.getSeverity()).isEqualTo(Severity.SEV1);
        assertThat(incident.getTeamId()).isEqualTo(PLATFORM);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    void escalationWithinTheTeamKeepsStatus() {
        incident.acknowledge(NOW);

        incident.escalate(Severity.SEV1, null, NOW);

        assertThat(incident.getTeamId()).isEqualTo(DATABASE);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
    }

    @Test
    void escalationMustRaiseSeverityAndChangeNothingOtherwise() {
        assertThatThrownBy(() -> incident.escalate(Severity.SEV2, PLATFORM, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.escalate(Severity.SEV3, null, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.escalate(Severity.SEV1, DATABASE, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(incident.getSeverity()).isEqualTo(Severity.SEV2);
        assertThat(incident.getTeamId()).isEqualTo(DATABASE);
    }

    @Test
    void commentsAreKeptInOrder() {
        incident.addComment(BOB.id(), "first", NOW);
        incident.addComment(DAN.id(), "second", NOW);

        assertThat(incident.toView().comments()).extracting(CommentView::text).containsExactly("first", "second");
    }
}
