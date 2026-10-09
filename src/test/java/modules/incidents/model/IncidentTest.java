package modules.incidents.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static modules.TestData.*;

import java.util.UUID;

import modules.common.exception.BusinessRuleException;
import modules.common.model.Severity;
import org.junit.jupiter.api.Test;

class IncidentTest {

    private final Incident incident = new Incident(UUID.randomUUID(), "DB down", null, DATABASE_CATEGORY, "Database",
            DATABASE, BOB.id(), Severity.SEV2, NOW);

    @Test
    void startsOpenWithEmptyDescription() {
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.toView().description()).isEmpty();
    }

    private void acknowledge() {
        incident.changeStatus(IncidentStatus.IN_PROGRESS, DAN.id(), null, NOW);
    }

    private void resolved() {
        acknowledge();
        incident.changeStatus(IncidentStatus.IN_REVIEW, DAN.id(), null, NOW);
        incident.changeStatus(IncidentStatus.RESOLVED, DAN.id(), "done", NOW);
    }

    @Test
    void followsTheLifecycle() {
        acknowledge();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(incident.toView().acknowledgedAt()).isEqualTo(NOW);

        incident.changeStatus(IncidentStatus.IN_REVIEW, DAN.id(), null, NOW);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_REVIEW);

        incident.changeStatus(IncidentStatus.RESOLVED, DAN.id(), " fixed index ", NOW);
        IncidentView view = incident.toView();
        assertThat(view.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(view.resolvedBy()).isEqualTo(DAN.id());
        assertThat(view.resolutionNote()).isEqualTo("fixed index");

        incident.changeStatus(IncidentStatus.CLOSED, DAN.id(), null, NOW);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(incident.toView().closedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyAReviewedIncidentCanBeResolved() {
        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.RESOLVED, DAN.id(), "duplicate", NOW))
                .isInstanceOf(BusinessRuleException.class);
        acknowledge();
        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.RESOLVED, DAN.id(), "fixed", NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reviewCanSendItBackToWork() {
        acknowledge();
        incident.changeStatus(IncidentStatus.IN_REVIEW, DAN.id(), null, NOW);

        incident.changeStatus(IncidentStatus.IN_PROGRESS, DAN.id(), null, NOW);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
    }

    @Test
    void reopenNeedsAReasonAndClearsTheResolution() {
        resolved();

        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.IN_PROGRESS, DAN.id(), " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        incident.changeStatus(IncidentStatus.IN_PROGRESS, DAN.id(), "came back", NOW);

        IncidentView view = incident.toView();
        assertThat(view.status()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(view.resolvedAt()).isNull();
        assertThat(view.resolvedBy()).isNull();
        assertThat(view.resolutionNote()).isNull();
    }

    @Test
    void cancelNeedsAReasonAndIsFinal() {
        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.CANCELLED, DAN.id(), null, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        incident.changeStatus(IncidentStatus.CANCELLED, DAN.id(), "duplicate", NOW);

        assertThat(incident.toView().closedAt()).isEqualTo(NOW);
        for (IncidentStatus target : IncidentStatus.values()) {
            assertThatThrownBy(() -> incident.changeStatus(target, DAN.id(), "again", NOW))
                    .isInstanceOf(BusinessRuleException.class);
        }
    }

    @Test
    void closedIsFinal() {
        resolved();
        incident.changeStatus(IncidentStatus.CLOSED, DAN.id(), null, NOW);

        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.IN_PROGRESS, DAN.id(), "again", NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resolvedCannotBeCancelled() {
        resolved();

        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.CANCELLED, DAN.id(), "oops", NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void detailsCanBeEditedPartly() {
        incident.updateDetails(" Primary DB down ", null, NOW);
        assertThat(incident.toView().title()).isEqualTo("Primary DB down");
        assertThat(incident.toView().description()).isEmpty();

        incident.updateDetails(null, "timeouts on writes", NOW);
        assertThat(incident.toView().title()).isEqualTo("Primary DB down");
        assertThat(incident.toView().description()).isEqualTo("timeouts on writes");

        assertThatThrownBy(() -> incident.updateDetails("Primary DB down", null, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.updateDetails(" ", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deEscalationLowersSeverityAndKeepsTeamAndStatus() {
        acknowledge();

        incident.deEscalate(Severity.SEV4, NOW);

        assertThat(incident.toView().severity()).isEqualTo(Severity.SEV4);
        assertThat(incident.getTeamId()).isEqualTo(DATABASE);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThatThrownBy(() -> incident.deEscalate(Severity.SEV4, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.deEscalate(Severity.SEV1, NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void acknowledgeOnlyWhenOpen() {
        acknowledge();

        assertThatThrownBy(this::acknowledge).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resolvedIncidentIsReadOnly() {
        resolved();

        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.RESOLVED, DAN.id(), "again", NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.deEscalate(Severity.SEV3, NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.addComment(DAN.id(), "late", NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.escalate(Severity.SEV1, PLATFORM, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.updateDetails("new", null, NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> incident.escalate(Severity.SEV1, null, NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resolveNeedsANote() {
        acknowledge();
        incident.changeStatus(IncidentStatus.IN_REVIEW, DAN.id(), null, NOW);

        assertThatThrownBy(() -> incident.changeStatus(IncidentStatus.RESOLVED, DAN.id(), " ", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void escalationRaisesSeverityAndMayHandOver() {
        acknowledge();

        incident.escalate(Severity.SEV1, PLATFORM, NOW);

        IncidentView view = incident.toView();
        assertThat(view.severity()).isEqualTo(Severity.SEV1);
        assertThat(view.teamId()).isEqualTo(PLATFORM);
        assertThat(view.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(view.acknowledgedAt()).isNull();
    }

    @Test
    void escalationWithinTheTeamKeepsStatus() {
        acknowledge();

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
        assertThat(incident.toView().severity()).isEqualTo(Severity.SEV2);
        assertThat(incident.getTeamId()).isEqualTo(DATABASE);
    }

    @Test
    void commentsAreKeptInOrder() {
        incident.addComment(BOB.id(), "first", NOW);
        incident.addComment(DAN.id(), "second", NOW);

        assertThat(incident.toView().comments()).extracting(CommentView::text).containsExactly("first", "second");
    }
}
