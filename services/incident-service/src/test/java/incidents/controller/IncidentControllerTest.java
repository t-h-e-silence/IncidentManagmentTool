package incidents.controller;

import static incidents.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import incidents.model.AddCommentCommand;
import incidents.model.CategoryRouting;
import incidents.model.CommentView;
import incidents.model.Incident;
import incidents.model.IncidentChange;
import incidents.model.IncidentEvent;
import incidents.model.IncidentStatus;
import incidents.model.IncidentView;
import incidents.model.ReportIncidentCommand;
import incidents.model.Severity;
import incidents.model.UpdateIncidentCommand;
import incidents.service.IdempotencyService;
import incidents.service.IncidentService;
import incidents.service.OrganizationClient;
import incidents.service.OutboxService;
import incidents.web.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What {@link IncidentController} does per action: the checks, the incident change, and one outbox event per
 * change, sharing the request's correlation id. The HTTP side is covered by {@link IncidentControllerHttpTest}.
 */
@ExtendWith(MockitoExtension.class)
class IncidentControllerTest {

    @Mock
    OrganizationClient organization;
    @Mock
    IncidentService incidents;
    @Mock
    OutboxService outbox;
    @Mock
    IdempotencyService idempotency;

    private IncidentController controller;

    private final IncidentView open = incident(Severity.SEV3);

    @BeforeEach
    void setUp() {
        controller = new IncidentController(organization, incidents, outbox, idempotency, CLOCK);
        // Without a key the action just runs; not every test writes.
        lenient().doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(5)).get())
                .when(idempotency).execute(any(), any(), anyString(), any(), any(), any());
    }

    private static IncidentView incident(Severity severity) {
        return new Incident(UUID.fromString("40000000-0000-0000-0000-000000000001"), "Checkout is slow", "",
                DATABASE_CATEGORY, "Database", DATABASE, BOB.id(), severity, NOW).toView();
    }

    private static IncidentView with(IncidentView view, Severity severity, UUID teamId, IncidentStatus status) {
        return new IncidentView(view.id(), view.title(), view.description(), view.categoryId(), view.categoryName(),
                teamId, view.reporterId(), severity, status, view.createdAt(), NOW, null, null, null, null, null,
                List.of());
    }

    private List<IncidentEvent> published(int count) {
        ArgumentCaptor<IncidentEvent> events = ArgumentCaptor.forClass(IncidentEvent.class);
        verify(outbox, times(count)).publish(events.capture());
        return events.getAllValues();
    }

    @Test
    void createRoutesByCategoryAndPublishesCreated() {
        ReportIncidentCommand command = new ReportIncidentCommand("Checkout is slow", null, DATABASE_CATEGORY,
                Severity.SEV2);
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        IncidentView created = incident(Severity.SEV2);
        when(incidents.create(BOB, command, "Database", DATABASE)).thenReturn(created);

        assertThat(controller.createIncident(BOB.id(), null, command)).isEqualTo(created);

        IncidentEvent event = published(1).get(0);
        assertThat(event.type()).isEqualTo(IncidentEvent.CREATED);
        assertThat(event.routingKey()).isEqualTo("incident.created.urgent");
        assertThat(event.actor().name()).isEqualTo("Bob Reporter");
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void severalChangesPublishOneEventEachWithOneCorrelationId() {
        IncidentView renamed = new IncidentView(open.id(), "Primary DB slow", "", open.categoryId(),
                open.categoryName(), DATABASE, BOB.id(), Severity.SEV3, IncidentStatus.OPEN, NOW, NOW, null, null,
                null, null, null, List.of());
        IncidentView escalated = with(renamed, Severity.SEV1, DATABASE, IncidentStatus.OPEN);
        IncidentView acknowledged = with(escalated, Severity.SEV1, DATABASE, IncidentStatus.IN_PROGRESS);
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.get(open.id())).thenReturn(open);
        when(incidents.updateDetails(DAN, open.id(), "Primary DB slow", null))
                .thenReturn(new IncidentChange(open, renamed));
        when(incidents.escalate(DAN, open.id(), Severity.SEV1, null))
                .thenReturn(new IncidentChange(renamed, escalated));
        when(incidents.changeStatus(DAN, open.id(), IncidentStatus.IN_PROGRESS, "disk full"))
                .thenReturn(new IncidentChange(escalated, acknowledged));

        IncidentView result = controller.updateIncident(DAN.id(), open.id(), null, new UpdateIncidentCommand(
                "Primary DB slow", null, Severity.SEV1, null, IncidentStatus.IN_PROGRESS, "disk full"));

        assertThat(result).isEqualTo(acknowledged);
        List<IncidentEvent> events = published(3);
        assertThat(events).extracting(IncidentEvent::type).containsExactly(IncidentEvent.DETAILS_UPDATED,
                IncidentEvent.ESCALATED, IncidentEvent.STATUS_CHANGED);
        assertThat(events).extracting(IncidentEvent::correlationId).containsOnly(events.get(0).correlationId());
        assertThat(events.get(0).routingKey()).isEqualTo("incident.details-updated.normal");
        assertThat(events.get(1).data()).containsEntry("fromSeverity", Severity.SEV3)
                .containsEntry("toSeverity", Severity.SEV1).containsEntry("reason", "disk full");
        assertThat(events.get(2).data()).containsEntry("fromStatus", IncidentStatus.OPEN)
                .containsEntry("toStatus", IncidentStatus.IN_PROGRESS).containsEntry("note", "disk full");
    }

    @Test
    void handOverChecksTheTargetTeamFirst() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.get(open.id())).thenReturn(open);
        doAnswer(invocation -> {
            throw new BusinessRuleException("Team " + PLATFORM + " does not exist or is archived");
        }).when(organization).requireActiveTeam(PLATFORM);

        assertThatThrownBy(() -> controller.updateIncident(DAN.id(), open.id(), null,
                new UpdateIncidentCommand(null, null, Severity.SEV1, PLATFORM, null, "payments")))
                .isInstanceOf(BusinessRuleException.class);
        verify(incidents, never()).escalate(any(), any(), any(), any());
        verify(outbox, never()).publish(any());
    }

    @Test
    void escalationWithHandOverCarriesBothTeams() {
        IncidentView handedOver = with(open, Severity.SEV1, PLATFORM, IncidentStatus.OPEN);
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.get(open.id())).thenReturn(open);
        when(incidents.escalate(DAN, open.id(), Severity.SEV1, PLATFORM))
                .thenReturn(new IncidentChange(open, handedOver));

        controller.updateIncident(DAN.id(), open.id(), null,
                new UpdateIncidentCommand(null, null, Severity.SEV1, PLATFORM, null, "payments pool"));

        IncidentEvent event = published(1).get(0);
        assertThat(event.routingKey()).isEqualTo("incident.escalated.urgent");
        assertThat(event.data()).containsEntry("fromTeamId", DATABASE).containsEntry("toTeamId", PLATFORM);
    }

    @Test
    void deEscalationCannotHandOver() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.get(open.id())).thenReturn(open);

        assertThatThrownBy(() -> controller.updateIncident(DAN.id(), open.id(), null,
                new UpdateIncidentCommand(null, null, Severity.SEV4, PLATFORM, null, "contained")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Only an escalation");
        verify(outbox, never()).publish(any());
    }

    @Test
    void sameSeverityIsRejected() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.get(open.id())).thenReturn(open);

        assertThatThrownBy(() -> controller.updateIncident(DAN.id(), open.id(), null,
                new UpdateIncidentCommand(null, null, Severity.SEV3, null, null, "same")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Severity is already SEV3");
    }

    @Test
    void commentPublishesCommentAdded() {
        CommentView comment = new CommentView(UUID.randomUUID(), open.id(), BOB.id(), "Also on mobile", NOW);
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(incidents.addComment(BOB, open.id(), "Also on mobile")).thenReturn(comment);
        when(incidents.get(open.id())).thenReturn(open);

        assertThat(controller.addComment(BOB.id(), open.id(), null, new AddCommentCommand("Also on mobile")))
                .isEqualTo(comment);

        IncidentEvent event = published(1).get(0);
        assertThat(event.type()).isEqualTo(IncidentEvent.COMMENT_ADDED);
        assertThat(event.data()).containsEntry("commentId", comment.id());
    }

    @Test
    void writesGoThroughTheIdempotencyKey() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        CommentView stored = new CommentView(UUID.randomUUID(), open.id(), BOB.id(), "Also on mobile", NOW);
        doAnswer(invocation -> stored).when(idempotency)
                .execute(any(), any(), anyString(), any(), any(), any());

        assertThat(controller.addComment(BOB.id(), open.id(), "key-1", new AddCommentCommand("Also on mobile")))
                .isEqualTo(stored);

        verify(idempotency).execute(eq("key-1"), eq(BOB.id()), eq("POST /incidents/" + open.id() + "/comments"),
                any(), eq(CommentView.class), any());
        verify(incidents, never()).addComment(any(), any(), any());
        verify(outbox, never()).publish(any());
    }

    @Test
    void listsCheckTheCallerAndTheTeam() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.listByTeam(DATABASE)).thenReturn(List.of());

        assertThat(controller.listTeamIncidents(DAN.id(), DATABASE)).isEmpty();

        verify(organization).getTeam(DATABASE);
    }
}
