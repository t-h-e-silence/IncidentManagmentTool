package org.example.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.TestData.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.audit.model.AuditAction;
import org.example.audit.model.AuditRecord;
import org.example.audit.service.AuditService;
import org.example.common.exception.BusinessRuleException;
import org.example.common.exception.ForbiddenException;
import org.example.common.exception.UnauthenticatedException;
import org.example.common.model.Recipient;
import org.example.common.model.Severity;
import org.example.controller.model.EscalateCommand;
import org.example.escalations.model.EscalationRecipients;
import org.example.escalations.model.EscalationRecord;
import org.example.escalations.service.EscalationService;
import org.example.incidents.model.IncidentChange;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;
import org.example.incidents.service.IncidentService;
import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.NotificationReason;
import org.example.notifications.model.NotificationStatus;
import org.example.notifications.model.NotificationView;
import org.example.notifications.service.NotificationService;
import org.example.organization.model.CategoryRouting;
import org.example.organization.model.TeamView;
import org.example.organization.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncidentManagementControllerTest {

    @Mock
    OrganizationService organization;
    @Mock
    IncidentService incidents;
    @Mock
    AuditService audit;
    @Mock
    NotificationService notifications;
    @Mock
    EscalationService escalations;
    @InjectMocks
    IncidentManagementController controller;

    private final UUID incidentId = UUID.randomUUID();
    private final ReportIncidentCommand report =
            new ReportIncidentCommand("DB down", "timeouts", DATABASE_CATEGORY, Severity.SEV2);

    private IncidentView incident(UUID teamId, IncidentStatus status, Severity severity) {
        return new IncidentView(incidentId, "DB down", "timeouts", DATABASE_CATEGORY, "Database", teamId, BOB.id(),
                severity, status, NOW, NOW, null, null, null, null, List.of());
    }

    private void teamName(UUID teamId, String name) {
        when(organization.getTeam(teamId)).thenReturn(new TeamView(teamId, name, false, List.of()));
    }

    @Test
    void reportCreatesAuditsAndNotifiesTheTeamInOrder() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        IncidentView created = incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2);
        when(incidents.create(BOB, report, "Database", DATABASE)).thenReturn(created);
        teamName(DATABASE, "Database");
        List<Recipient> team = List.of(recipient(ALICE), recipient(DAN));
        when(organization.getActiveMembers(DATABASE)).thenReturn(team);

        assertThat(controller.reportIncident(BOB.id(), report)).isEqualTo(created);

        InOrder order = inOrder(incidents, audit, notifications);
        order.verify(incidents).create(BOB, report, "Database", DATABASE);
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        order.verify(audit).record(record.capture());
        ArgumentCaptor<IncidentNotice> notice = ArgumentCaptor.forClass(IncidentNotice.class);
        order.verify(notifications).notifyIncident(notice.capture(), eq(team));

        assertThat(record.getValue().action()).isEqualTo(AuditAction.INCIDENT_CREATED);
        assertThat(record.getValue().actorId()).isEqualTo(BOB.id());
        assertThat(record.getValue().incidentId()).isEqualTo(incidentId);
        assertThat(notice.getValue().reason()).isEqualTo(NotificationReason.INCIDENT_CREATED);
        assertThat(notice.getValue().teamName()).isEqualTo("Database");
    }

    @Test
    void failedCreateIsNeitherAuditedNorNotified() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        when(incidents.create(any(), any(), any(), any())).thenThrow(new BusinessRuleException("SEV1 not allowed"));

        assertThatThrownBy(() -> controller.reportIncident(BOB.id(), report)).isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(audit, notifications);
    }

    @Test
    void unknownActorStopsEverything() {
        UUID stranger = UUID.randomUUID();
        when(organization.getActiveActor(stranger)).thenThrow(new UnauthenticatedException("unknown"));

        assertThatThrownBy(() -> controller.reportIncident(stranger, report))
                .isInstanceOf(UnauthenticatedException.class);

        verifyNoInteractions(incidents, audit, notifications);
    }

    @Test
    void teamWithoutActiveMembersNotifiesAdmins() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        when(incidents.create(BOB, report, "Database", DATABASE))
                .thenReturn(incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2));
        teamName(DATABASE, "Database");
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of());
        when(organization.getActiveAdmins()).thenReturn(List.of(recipient(ADA)));

        controller.reportIncident(BOB.id(), report);

        verify(notifications).notifyIncident(any(), eq(List.of(recipient(ADA))));
    }

    @Test
    void acknowledgeNotifiesOnlyTheReporter() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.acknowledge(DAN, incidentId)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2),
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2)));
        teamName(DATABASE, "Database");
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        controller.acknowledgeIncident(DAN.id(), incidentId);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().details()).containsEntry("from", "OPEN").containsEntry("to", "IN_PROGRESS");
        ArgumentCaptor<IncidentNotice> notice = ArgumentCaptor.forClass(IncidentNotice.class);
        verify(notifications).notifyIncident(notice.capture(),
                eq(List.of(recipient(BOB))));
        assertThat(notice.getValue().reason()).isEqualTo(NotificationReason.INCIDENT_ACKNOWLEDGED);
    }

    @Test
    void resolveNotifiesTeamAndReporterOnce() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.resolve(DAN, incidentId, "fixed")).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2),
                incident(DATABASE, IncidentStatus.RESOLVED, Severity.SEV2)));
        teamName(DATABASE, "Database");
        // the reporter is also in the team: emailed once
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(DAN), recipient(BOB)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        controller.resolveIncident(DAN.id(), incidentId, "fixed");

        verify(notifications).notifyIncident(any(),
                eq(List.of(recipient(DAN), recipient(BOB))));
    }

    @Test
    void changeSeverityIsAuditedWithoutEmails() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.changeSeverity(DAN, incidentId, Severity.SEV3)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2),
                incident(DATABASE, IncidentStatus.OPEN, Severity.SEV3)));

        controller.changeSeverity(DAN.id(), incidentId, Severity.SEV3);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().action()).isEqualTo(AuditAction.SEVERITY_CHANGED);
        assertThat(record.getValue().details()).containsEntry("from", "SEV2").containsEntry("to", "SEV3");
        verifyNoInteractions(notifications);
    }

    @Test
    void reassignChecksTargetTeamFirstAndNotifiesNewTeam() {
        when(organization.getActiveActor(ALICE.id())).thenReturn(ALICE);
        when(incidents.reassign(ALICE, incidentId, PLATFORM)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2),
                incident(PLATFORM, IncidentStatus.OPEN, Severity.SEV2)));
        teamName(PLATFORM, "Platform");
        when(organization.getActiveMembers(PLATFORM)).thenReturn(List.of(recipient(CAROL)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        controller.reassignIncident(ALICE.id(), incidentId, PLATFORM, "payments side");

        InOrder order = inOrder(organization, incidents);
        order.verify(organization).requireActiveTeam(PLATFORM);
        order.verify(incidents).reassign(ALICE, incidentId, PLATFORM);
        ArgumentCaptor<IncidentNotice> notice = ArgumentCaptor.forClass(IncidentNotice.class);
        verify(notifications).notifyIncident(notice.capture(),
                eq(List.of(recipient(CAROL), recipient(BOB))));
        assertThat(notice.getValue().teamName()).isEqualTo("Platform");
        assertThat(notice.getValue().note()).isEqualTo("payments side");
    }

    @Test
    void reassignToInactiveTeamChangesNothing() {
        when(organization.getActiveActor(ALICE.id())).thenReturn(ALICE);
        doThrow(new BusinessRuleException("archived"))
                .when(organization).requireActiveTeam(PLATFORM);

        assertThatThrownBy(() -> controller.reassignIncident(ALICE.id(), incidentId, PLATFORM, "x"))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(incidents, audit, notifications);
    }

    @Test
    void forbiddenChangeIsNeitherAuditedNorNotified() {
        when(organization.getActiveActor(CAROL.id())).thenReturn(CAROL);
        when(incidents.acknowledge(CAROL, incidentId)).thenThrow(new ForbiddenException("not your team"));

        assertThatThrownBy(() -> controller.acknowledgeIncident(CAROL.id(), incidentId))
                .isInstanceOf(ForbiddenException.class);

        verify(audit, never()).record(any());
        verify(notifications, never()).notifyIncident(any(), anyList());
    }

    @Test
    void escalationWithHandOverAuditsThenLetsEscalationsEmail() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        EscalateCommand command = new EscalateCommand(Severity.SEV1, PLATFORM, "replica lag growing");
        when(incidents.escalate(DAN, incidentId, Severity.SEV1, PLATFORM)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV3),
                incident(PLATFORM, IncidentStatus.OPEN, Severity.SEV1)));
        teamName(DATABASE, "Database");
        teamName(PLATFORM, "Platform");
        when(organization.getActiveMembers(PLATFORM)).thenReturn(List.of(recipient(CAROL)));
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(ALICE), recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        IncidentView result = controller.escalateIncident(DAN.id(), incidentId, command);

        assertThat(result.teamId()).isEqualTo(PLATFORM);
        InOrder order = inOrder(organization, incidents, audit, escalations);
        order.verify(organization).requireActiveTeam(PLATFORM);
        order.verify(incidents).escalate(DAN, incidentId, Severity.SEV1, PLATFORM);
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        order.verify(audit).record(record.capture());
        ArgumentCaptor<EscalationRecord> escalation = ArgumentCaptor.forClass(EscalationRecord.class);
        ArgumentCaptor<EscalationRecipients> recipients = ArgumentCaptor.forClass(EscalationRecipients.class);
        order.verify(escalations).recordAndNotify(escalation.capture(), recipients.capture());

        assertThat(record.getValue().action()).isEqualTo(AuditAction.ESCALATED);
        assertThat(record.getValue().details()).containsEntry("fromSeverity", "SEV3")
                .containsEntry("toSeverity", "SEV1").containsEntry("reason", "replica lag growing");
        assertThat(escalation.getValue().fromTeamName()).isEqualTo("Database");
        assertThat(escalation.getValue().toTeamName()).isEqualTo("Platform");
        assertThat(escalation.getValue().actorName()).isEqualTo("Dan Dba");
        assertThat(recipients.getValue().owningTeam()).containsExactly(recipient(CAROL));
        assertThat(recipients.getValue().previousTeam()).containsExactly(recipient(ALICE), recipient(DAN));
        assertThat(recipients.getValue().reporter()).contains(recipient(BOB));
        verify(notifications, never()).notifyIncident(any(), anyList());
    }

    @Test
    void escalationWithinTheTeamHasNoPreviousTeam() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        when(incidents.escalate(DAN, incidentId, Severity.SEV1, null)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2),
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV1)));
        teamName(DATABASE, "Database");
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(ALICE), recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.empty());

        controller.escalateIncident(DAN.id(), incidentId, new EscalateCommand(Severity.SEV1, null, "worse"));

        ArgumentCaptor<EscalationRecipients> recipients = ArgumentCaptor.forClass(EscalationRecipients.class);
        verify(escalations).recordAndNotify(any(), recipients.capture());
        assertThat(recipients.getValue().previousTeam()).isEmpty();
        assertThat(recipients.getValue().reporter()).isEmpty();
        verify(organization, never()).requireActiveTeam(any());
    }

    @Test
    void escalationWithoutReasonIsRejectedBeforeAnything() {
        assertThatThrownBy(() -> new EscalateCommand(Severity.SEV1, null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusedEscalationIsNeitherAuditedNorEmailed() {
        when(organization.getActiveActor(CAROL.id())).thenReturn(CAROL);
        when(incidents.escalate(CAROL, incidentId, Severity.SEV1, null)).thenThrow(new ForbiddenException("no"));

        assertThatThrownBy(() -> controller.escalateIncident(CAROL.id(), incidentId,
                new EscalateCommand(Severity.SEV1, null, "urgent"))).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(audit, escalations, notifications);
    }

    @Test
    void adminFunctionsRejectNonAdmins() {
        when(organization.getActiveActor(ALICE.id())).thenReturn(ALICE);

        assertThatThrownBy(() -> controller.listDeadLetteredNotifications(ALICE.id()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.replayNotification(ALICE.id(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.getUserActivity(ALICE.id(), BOB.id()))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(notifications, audit);
    }

    @Test
    void replayIsAuditedOnTheIncidentTimeline() {
        when(organization.getActiveActor(ADA.id())).thenReturn(ADA);
        UUID notificationId = UUID.randomUUID();
        NotificationView replayed = new NotificationView(notificationId, incidentId, DAN.id(), "dan@example.com",
                NotificationReason.INCIDENT_CREATED, "subject", NotificationStatus.PENDING, 0, NOW, null, NOW, null);
        when(notifications.replay(notificationId)).thenReturn(replayed);

        assertThat(controller.replayNotification(ADA.id(), notificationId)).isEqualTo(replayed);

        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(record.capture());
        assertThat(record.getValue().action()).isEqualTo(AuditAction.NOTIFICATION_REPLAYED);
        assertThat(record.getValue().actorId()).isEqualTo(ADA.id());
        assertThat(record.getValue().entityId()).isEqualTo(notificationId);
        assertThat(record.getValue().incidentId()).isEqualTo(incidentId);
    }

    @Test
    void adminSeesUserActivity() {
        when(organization.getActiveActor(ADA.id())).thenReturn(ADA);
        when(audit.getActionsByUser(BOB.id())).thenReturn(List.of());

        assertThat(controller.getUserActivity(ADA.id(), BOB.id())).isEmpty();
    }

    @Test
    void timelineChecksTheIncidentExists() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(incidents.get(incidentId)).thenReturn(incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2));
        when(audit.getIncidentTimeline(incidentId)).thenReturn(List.of());

        assertThat(controller.getIncidentTimeline(BOB.id(), incidentId)).isEmpty();
        verify(incidents).get(incidentId);
    }
}
