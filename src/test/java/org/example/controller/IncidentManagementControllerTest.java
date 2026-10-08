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
import static org.mockito.Mockito.times;
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
import org.example.common.exception.NotFoundException;
import org.example.common.exception.UnauthenticatedException;
import org.example.common.model.Recipient;
import org.example.common.model.Severity;
import org.example.controller.model.AddCommentCommand;
import org.example.controller.model.UpdateIncidentCommand;
import org.example.escalations.model.EscalationRecipients;
import org.example.escalations.model.EscalationRecord;
import org.example.escalations.service.EscalationService;
import org.example.incidents.model.CommentView;
import org.example.incidents.model.IncidentChange;
import org.example.incidents.model.IncidentStatus;
import org.example.incidents.model.IncidentSummary;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;
import org.example.incidents.service.IncidentService;
import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.NotificationReason;
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
        return incident("DB down", teamId, status, severity);
    }

    private IncidentView incident(String title, UUID teamId, IncidentStatus status, Severity severity) {
        return new IncidentView(incidentId, title, "timeouts", DATABASE_CATEGORY, "Database", teamId, BOB.id(),
                severity, status, NOW, NOW, null, null, null, null, null, List.of());
    }

    private void teamName(UUID teamId, String name) {
        when(organization.getTeam(teamId)).thenReturn(new TeamView(teamId, name, false, List.of()));
    }

    private void current(IncidentStatus status, Severity severity) {
        when(incidents.get(incidentId)).thenReturn(incident(DATABASE, status, severity));
    }

    private static UpdateIncidentCommand severity(Severity severity, UUID targetTeamId, String reason) {
        return new UpdateIncidentCommand(null, null, severity, targetTeamId, null, reason);
    }

    private static UpdateIncidentCommand status(IncidentStatus status, String reason) {
        return new UpdateIncidentCommand(null, null, null, null, status, reason);
    }

    private List<AuditRecord> audited(int times) {
        ArgumentCaptor<AuditRecord> records = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit, times(times)).record(records.capture());
        return records.getAllValues();
    }

    // ---------------------------------------------------------------- lists

    @Test
    void listsTeamsAndIncidents() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        TeamView database = new TeamView(DATABASE, "Database", false, List.of());
        when(organization.listTeams()).thenReturn(List.of(database));
        when(organization.getTeam(DATABASE)).thenReturn(database);
        when(organization.findUserId("dan")).thenReturn(Optional.of(DAN.id()));
        IncidentSummary summary = new IncidentSummary(incidentId, "DB down", DATABASE, DAN.id(), Severity.SEV2,
                IncidentStatus.CLOSED, NOW);
        when(incidents.listAll()).thenReturn(List.of(summary));
        when(incidents.listByTeam(DATABASE)).thenReturn(List.of(summary));
        when(incidents.listReportedBy(DAN.id())).thenReturn(List.of(summary));

        assertThat(controller.listTeams(BOB.id())).containsExactly(database);
        assertThat(controller.listAllIncidents(BOB.id())).containsExactly(summary);
        assertThat(controller.listTeamIncidents(BOB.id(), DATABASE)).containsExactly(summary);
        assertThat(controller.listUserIncidents(BOB.id(), "dan")).containsExactly(summary);
    }

    @Test
    void unknownUserInListIsNotFound() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.findUserId("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listUserIncidents(BOB.id(), "nobody"))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------- create

    @Test
    void createAuditsAndNotifiesTheTeamInOrder() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        IncidentView created = incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2);
        when(incidents.create(BOB, report, "Database", DATABASE)).thenReturn(created);
        teamName(DATABASE, "Database");
        List<Recipient> team = List.of(recipient(ALICE), recipient(DAN));
        when(organization.getActiveMembers(DATABASE)).thenReturn(team);

        assertThat(controller.createIncident(BOB.id(), report)).isEqualTo(created);

        InOrder order = inOrder(incidents, audit, notifications);
        order.verify(incidents).create(BOB, report, "Database", DATABASE);
        ArgumentCaptor<AuditRecord> record = ArgumentCaptor.forClass(AuditRecord.class);
        order.verify(audit).record(record.capture());
        ArgumentCaptor<IncidentNotice> notice = ArgumentCaptor.forClass(IncidentNotice.class);
        order.verify(notifications).notifyIncident(notice.capture(), eq(team));
        assertThat(record.getValue().action()).isEqualTo(AuditAction.INCIDENT_CREATED);
        assertThat(record.getValue().actorId()).isEqualTo(BOB.id());
        assertThat(notice.getValue().reason()).isEqualTo(NotificationReason.INCIDENT_CREATED);
    }

    @Test
    void failedCreateIsNeitherAuditedNorNotified() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(organization.getRouting(DATABASE_CATEGORY))
                .thenReturn(new CategoryRouting(DATABASE_CATEGORY, "Database", DATABASE));
        when(incidents.create(any(), any(), any(), any())).thenThrow(new BusinessRuleException("SEV1 not allowed"));

        assertThatThrownBy(() -> controller.createIncident(BOB.id(), report)).isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(audit, notifications);
    }

    @Test
    void unknownActorStopsEverything() {
        UUID stranger = UUID.randomUUID();
        when(organization.getActiveActor(stranger)).thenThrow(new UnauthenticatedException("unknown"));

        assertThatThrownBy(() -> controller.createIncident(stranger, report))
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

        controller.createIncident(BOB.id(), report);

        verify(notifications).notifyIncident(any(), eq(List.of(recipient(ADA))));
    }

    // ---------------------------------------------------------------- update: details

    @Test
    void titleUpdateIsAuditedWithoutEmails() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        current(IncidentStatus.OPEN, Severity.SEV2);
        IncidentView after = incident("Primary DB down", DATABASE, IncidentStatus.OPEN, Severity.SEV2);
        when(incidents.updateDetails(BOB, incidentId, "Primary DB down", null))
                .thenReturn(new IncidentChange(incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2), after));

        assertThat(controller.updateIncident(BOB.id(), incidentId,
                new UpdateIncidentCommand("Primary DB down", null, null, null, null, null))).isEqualTo(after);

        AuditRecord record = audited(1).get(0);
        assertThat(record.action()).isEqualTo(AuditAction.DETAILS_UPDATED);
        assertThat(record.details()).containsEntry("fromTitle", "DB down").containsEntry("toTitle", "Primary DB down")
                .containsEntry("descriptionChanged", "false");
        verifyNoInteractions(notifications, escalations);
    }

    @Test
    void emptyUpdateIsRejected() {
        assertThatThrownBy(() -> new UpdateIncidentCommand(null, null, null, null, null, "why"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- update: severity

    @Test
    void raisingSeverityEscalatesWithHandOver() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.IN_PROGRESS, Severity.SEV3);
        when(incidents.escalate(DAN, incidentId, Severity.SEV1, PLATFORM)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV3),
                incident(PLATFORM, IncidentStatus.OPEN, Severity.SEV1)));
        teamName(DATABASE, "Database");
        teamName(PLATFORM, "Platform");
        when(organization.getActiveMembers(PLATFORM)).thenReturn(List.of(recipient(CAROL)));
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(ALICE), recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        IncidentView result = controller.updateIncident(DAN.id(), incidentId,
                severity(Severity.SEV1, PLATFORM, "replica lag growing"));

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
        assertThat(recipients.getValue().owningTeam()).containsExactly(recipient(CAROL));
        assertThat(recipients.getValue().previousTeam()).containsExactly(recipient(ALICE), recipient(DAN));
        assertThat(recipients.getValue().reporter()).contains(recipient(BOB));
        verify(notifications, never()).notifyIncident(any(), anyList());
    }

    @Test
    void raisingSeverityWithinTheTeamHasNoPreviousTeam() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.IN_PROGRESS, Severity.SEV2);
        when(incidents.escalate(DAN, incidentId, Severity.SEV1, null)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2),
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV1)));
        teamName(DATABASE, "Database");
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(ALICE), recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.empty());

        controller.updateIncident(DAN.id(), incidentId, severity(Severity.SEV1, null, "worse"));

        ArgumentCaptor<EscalationRecipients> recipients = ArgumentCaptor.forClass(EscalationRecipients.class);
        verify(escalations).recordAndNotify(any(), recipients.capture());
        assertThat(recipients.getValue().previousTeam()).isEmpty();
        verify(organization, never()).requireActiveTeam(any());
    }

    @Test
    void loweringSeverityDeEscalatesAndEmails() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.IN_PROGRESS, Severity.SEV1);
        when(incidents.deEscalate(DAN, incidentId, Severity.SEV3)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV1),
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV3)));
        teamName(DATABASE, "Database");
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(ALICE), recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        controller.updateIncident(DAN.id(), incidentId, severity(Severity.SEV3, null, "contained"));

        assertThat(audited(1).get(0).action()).isEqualTo(AuditAction.DE_ESCALATED);
        ArgumentCaptor<EscalationRecord> escalation = ArgumentCaptor.forClass(EscalationRecord.class);
        verify(escalations).recordAndNotify(escalation.capture(), any());
        assertThat(escalation.getValue().reason()).isEqualTo("contained");
        assertThat(escalation.getValue().toSeverity()).isEqualTo(Severity.SEV3);
    }

    @Test
    void deEscalationCannotHandOver() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.IN_PROGRESS, Severity.SEV1);

        assertThatThrownBy(() -> controller.updateIncident(DAN.id(), incidentId,
                severity(Severity.SEV3, PLATFORM, "contained"))).isInstanceOf(BusinessRuleException.class);

        verify(incidents, never()).deEscalate(any(), any(), any());
        verifyNoInteractions(audit, escalations);
    }

    @Test
    void sameSeverityIsRejected() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.IN_PROGRESS, Severity.SEV2);

        assertThatThrownBy(() -> controller.updateIncident(DAN.id(), incidentId, severity(Severity.SEV2, null, "x")))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(audit, escalations);
    }

    @Test
    void severityNeedsAReasonAndHandOverNeedsASeverity() {
        assertThatThrownBy(() -> severity(Severity.SEV1, null, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpdateIncidentCommand(null, null, null, PLATFORM, null, "move"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusedEscalationIsNeitherAuditedNorEmailed() {
        when(organization.getActiveActor(CAROL.id())).thenReturn(CAROL);
        current(IncidentStatus.OPEN, Severity.SEV2);
        when(incidents.escalate(CAROL, incidentId, Severity.SEV1, null)).thenThrow(new ForbiddenException("no"));

        assertThatThrownBy(() -> controller.updateIncident(CAROL.id(), incidentId,
                severity(Severity.SEV1, null, "urgent"))).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(audit, escalations, notifications);
    }

    // ---------------------------------------------------------------- update: status

    @Test
    void acknowledgeNotifiesOnlyTheReporter() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.OPEN, Severity.SEV2);
        when(incidents.changeStatus(DAN, incidentId, IncidentStatus.IN_PROGRESS, null)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.OPEN, Severity.SEV2),
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2)));
        teamName(DATABASE, "Database");
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        controller.updateIncident(DAN.id(), incidentId, status(IncidentStatus.IN_PROGRESS, null));

        assertThat(audited(1).get(0).details()).containsEntry("from", "OPEN").containsEntry("to", "IN_PROGRESS");
        ArgumentCaptor<IncidentNotice> notice = ArgumentCaptor.forClass(IncidentNotice.class);
        verify(notifications).notifyIncident(notice.capture(), eq(List.of(recipient(BOB))));
        assertThat(notice.getValue().reason()).isEqualTo(NotificationReason.INCIDENT_ACKNOWLEDGED);
    }

    @Test
    void cancelIsAuditedWithItsReasonAndNotifiesTeamAndReporter() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.IN_PROGRESS, Severity.SEV2);
        when(incidents.changeStatus(DAN, incidentId, IncidentStatus.CANCELLED, "duplicate"))
                .thenReturn(new IncidentChange(
                        incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2),
                        incident(DATABASE, IncidentStatus.CANCELLED, Severity.SEV2)));
        teamName(DATABASE, "Database");
        // the reporter is also emailed, once
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(ALICE), recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        controller.updateIncident(DAN.id(), incidentId, status(IncidentStatus.CANCELLED, "duplicate"));

        assertThat(audited(1).get(0).details()).containsEntry("to", "CANCELLED").containsEntry("note", "duplicate");
        ArgumentCaptor<IncidentNotice> notice = ArgumentCaptor.forClass(IncidentNotice.class);
        verify(notifications).notifyIncident(notice.capture(),
                eq(List.of(recipient(ALICE), recipient(DAN), recipient(BOB))));
        assertThat(notice.getValue().reason()).isEqualTo(NotificationReason.INCIDENT_CANCELLED);
    }

    @Test
    void reopenNotifiesTeamAndReporter() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.RESOLVED, Severity.SEV2);
        when(incidents.changeStatus(DAN, incidentId, IncidentStatus.IN_PROGRESS, "came back"))
                .thenReturn(new IncidentChange(
                        incident(DATABASE, IncidentStatus.RESOLVED, Severity.SEV2),
                        incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2)));
        teamName(DATABASE, "Database");
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        controller.updateIncident(DAN.id(), incidentId, status(IncidentStatus.IN_PROGRESS, "came back"));

        ArgumentCaptor<IncidentNotice> notice = ArgumentCaptor.forClass(IncidentNotice.class);
        verify(notifications).notifyIncident(notice.capture(), eq(List.of(recipient(DAN), recipient(BOB))));
        assertThat(notice.getValue().reason()).isEqualTo(NotificationReason.INCIDENT_REOPENED);
    }

    @Test
    void reviewAndCloseAreAuditedWithoutEmails() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.IN_PROGRESS, Severity.SEV2);
        when(incidents.changeStatus(DAN, incidentId, IncidentStatus.IN_REVIEW, null)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2),
                incident(DATABASE, IncidentStatus.IN_REVIEW, Severity.SEV2)));
        when(incidents.changeStatus(DAN, incidentId, IncidentStatus.CLOSED, null)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.RESOLVED, Severity.SEV2),
                incident(DATABASE, IncidentStatus.CLOSED, Severity.SEV2)));

        controller.updateIncident(DAN.id(), incidentId, status(IncidentStatus.IN_REVIEW, null));
        controller.updateIncident(DAN.id(), incidentId, status(IncidentStatus.CLOSED, null));

        audited(2);
        verifyNoInteractions(notifications);
    }

    // ---------------------------------------------------------------- update: combined

    @Test
    void combinedUpdateRunsDetailsThenSeverityThenStatusWithOneCorrelationId() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.OPEN, Severity.SEV3);
        when(incidents.updateDetails(DAN, incidentId, "Primary DB down", null)).thenReturn(new IncidentChange(
                incident(DATABASE, IncidentStatus.OPEN, Severity.SEV3),
                incident("Primary DB down", DATABASE, IncidentStatus.OPEN, Severity.SEV3)));
        when(incidents.escalate(DAN, incidentId, Severity.SEV2, null)).thenReturn(new IncidentChange(
                incident("Primary DB down", DATABASE, IncidentStatus.OPEN, Severity.SEV3),
                incident("Primary DB down", DATABASE, IncidentStatus.OPEN, Severity.SEV2)));
        when(incidents.changeStatus(DAN, incidentId, IncidentStatus.IN_PROGRESS, "worse")).thenReturn(
                new IncidentChange(incident("Primary DB down", DATABASE, IncidentStatus.OPEN, Severity.SEV2),
                        incident("Primary DB down", DATABASE, IncidentStatus.IN_PROGRESS, Severity.SEV2)));
        teamName(DATABASE, "Database");
        when(organization.getActiveMembers(DATABASE)).thenReturn(List.of(recipient(DAN)));
        when(organization.findRecipient(BOB.id())).thenReturn(Optional.of(recipient(BOB)));

        IncidentView result = controller.updateIncident(DAN.id(), incidentId, new UpdateIncidentCommand(
                "Primary DB down", null, Severity.SEV2, null, IncidentStatus.IN_PROGRESS, "worse"));

        assertThat(result.status()).isEqualTo(IncidentStatus.IN_PROGRESS);
        InOrder order = inOrder(incidents);
        order.verify(incidents).updateDetails(DAN, incidentId, "Primary DB down", null);
        order.verify(incidents).escalate(DAN, incidentId, Severity.SEV2, null);
        order.verify(incidents).changeStatus(DAN, incidentId, IncidentStatus.IN_PROGRESS, "worse");
        List<AuditRecord> records = audited(3);
        assertThat(records).extracting(AuditRecord::action).containsExactly(AuditAction.DETAILS_UPDATED,
                AuditAction.ESCALATED, AuditAction.STATUS_CHANGED);
        assertThat(records).extracting(AuditRecord::correlationId).containsOnly(records.get(0).correlationId());
    }

    // ---------------------------------------------------------------- comment, view, history

    @Test
    void commentIsAudited() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        CommentView comment = new CommentView(UUID.randomUUID(), incidentId, BOB.id(), "more details", NOW);
        when(incidents.addComment(BOB, incidentId, "more details")).thenReturn(comment);

        assertThat(controller.addComment(BOB.id(), incidentId, new AddCommentCommand("more details")))
                .isEqualTo(comment);

        assertThat(audited(1).get(0).action()).isEqualTo(AuditAction.COMMENT_ADDED);
    }

    @Test
    void historyChecksTheIncidentExists() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        current(IncidentStatus.OPEN, Severity.SEV2);
        when(audit.getIncidentTimeline(incidentId)).thenReturn(List.of());

        assertThat(controller.getIncidentHistory(BOB.id(), incidentId)).isEmpty();
        verify(incidents).get(incidentId);
    }

    @Test
    void historyOfUnknownIncidentIsNotFound() {
        when(organization.getActiveActor(BOB.id())).thenReturn(BOB);
        when(incidents.get(incidentId)).thenThrow(new NotFoundException("no"));

        assertThatThrownBy(() -> controller.getIncidentHistory(BOB.id(), incidentId))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void escalationToInactiveTeamChangesNothing() {
        when(organization.getActiveActor(DAN.id())).thenReturn(DAN);
        current(IncidentStatus.OPEN, Severity.SEV2);
        doThrow(new BusinessRuleException("archived")).when(organization).requireActiveTeam(PLATFORM);

        assertThatThrownBy(() -> controller.updateIncident(DAN.id(), incidentId,
                severity(Severity.SEV1, PLATFORM, "x"))).isInstanceOf(BusinessRuleException.class);

        verify(incidents, never()).escalate(any(), any(), any(), any());
        verifyNoInteractions(audit, escalations, notifications);
    }
}
