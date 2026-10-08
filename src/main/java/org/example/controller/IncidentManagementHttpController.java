package org.example.controller;

import java.util.List;
import java.util.UUID;

import org.example.audit.model.AuditEntryView;
import org.example.common.exception.NotFoundException;
import org.example.controller.model.EscalateCommand;
import org.example.controller.model.HttpRequests;
import org.example.escalations.model.EscalationView;
import org.example.incidents.model.CommentView;
import org.example.incidents.model.IncidentSummary;
import org.example.incidents.model.IncidentView;
import org.example.incidents.model.ReportIncidentCommand;
import org.example.notifications.model.NotificationView;
import org.example.organization.model.CategoryView;
import org.example.organization.model.TeamView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP API over {@link IncidentManagementController}: every endpoint is one controller method and nothing else.
 * The caller is the user in the {@value #USER_HEADER} header, by id or username such as {@code bob} (no real
 * authentication; for Postman and manual tests). Users in paths may also be given by username.
 * Errors are mapped to status codes by {@link HttpErrorHandler}.
 */
@RestController
public class IncidentManagementHttpController {

    public static final String USER_HEADER = "X-User-Id";

    private final IncidentManagementController controller;

    public IncidentManagementHttpController(IncidentManagementController controller) {
        this.controller = controller;
    }

    // ---------------------------------------------------------------- organization

    @GetMapping("/categories")
    public List<CategoryView> listCategories(@ActingUser UUID actorId) {
        return controller.listCategories(actorId);
    }

    @GetMapping("/teams")
    public List<TeamView> listTeams(@ActingUser UUID actorId) {
        return controller.listTeams(actorId);
    }

    @GetMapping("/teams/{teamId}")
    public TeamView getTeam(@ActingUser UUID actorId, @PathVariable UUID teamId) {
        return controller.getTeam(actorId, teamId);
    }

    @GetMapping("/teams/{teamId}/queue")
    public List<IncidentSummary> getTeamQueue(@ActingUser UUID actorId, @PathVariable UUID teamId) {
        return controller.getTeamQueue(actorId, teamId);
    }

    @GetMapping("/teams/{teamId}/incidents")
    public List<IncidentSummary> listTeamIncidents(@ActingUser UUID actorId,
                                                   @PathVariable UUID teamId) {
        return controller.listTeamIncidents(actorId, teamId);
    }

    // ---------------------------------------------------------------- incidents

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentView reportIncident(@ActingUser UUID actorId,
                                       @RequestBody ReportIncidentCommand command) {
        return controller.reportIncident(actorId, command);
    }

    @GetMapping("/incidents")
    public List<IncidentSummary> listAllIncidents(@ActingUser UUID actorId) {
        return controller.listAllIncidents(actorId);
    }

    @GetMapping("/incidents/mine")
    public List<IncidentSummary> listMyReportedIncidents(@ActingUser UUID actorId) {
        return controller.listMyReportedIncidents(actorId);
    }

    @GetMapping("/users/{user}/incidents")
    public List<IncidentSummary> listIncidentsReportedBy(@ActingUser UUID actorId, @PathVariable String user) {
        return controller.listIncidentsReportedBy(actorId, userId(user));
    }

    @GetMapping("/incidents/{incidentId}")
    public IncidentView getIncident(@ActingUser UUID actorId, @PathVariable UUID incidentId) {
        return controller.getIncident(actorId, incidentId);
    }

    @PatchMapping("/incidents/{incidentId}")
    public IncidentView updateIncidentDetails(@ActingUser UUID actorId,
                                              @PathVariable UUID incidentId,
                                              @RequestBody HttpRequests.UpdateIncident body) {
        return controller.updateIncidentDetails(actorId, incidentId, body.title(), body.description());
    }

    @PostMapping("/incidents/{incidentId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView addComment(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                  @RequestBody HttpRequests.AddComment body) {
        return controller.addComment(actorId, incidentId, body.text());
    }

    @PostMapping("/incidents/{incidentId}/acknowledge")
    public IncidentView acknowledgeIncident(@ActingUser UUID actorId,
                                            @PathVariable UUID incidentId) {
        return controller.acknowledgeIncident(actorId, incidentId);
    }

    @PostMapping("/incidents/{incidentId}/resolve")
    public IncidentView resolveIncident(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                        @RequestBody HttpRequests.Resolve body) {
        return controller.resolveIncident(actorId, incidentId, body.note());
    }

    @PutMapping("/incidents/{incidentId}/status")
    public IncidentView changeIncidentStatus(@ActingUser UUID actorId,
                                             @PathVariable UUID incidentId,
                                             @RequestBody HttpRequests.ChangeStatus body) {
        return controller.changeIncidentStatus(actorId, incidentId, body.status(), body.note());
    }

    @PutMapping("/incidents/{incidentId}/severity")
    public IncidentView changeSeverity(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                       @RequestBody HttpRequests.ChangeSeverity body) {
        return controller.changeSeverity(actorId, incidentId, body.severity());
    }

    @PostMapping("/incidents/{incidentId}/escalate")
    public IncidentView escalateIncident(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                         @RequestBody EscalateCommand command) {
        return controller.escalateIncident(actorId, incidentId, command);
    }

    @PostMapping("/incidents/{incidentId}/de-escalate")
    public IncidentView deEscalateIncident(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                           @RequestBody EscalateCommand command) {
        return controller.deEscalateIncident(actorId, incidentId, command);
    }

    @PostMapping("/incidents/{incidentId}/reassign")
    public IncidentView reassignIncident(@ActingUser UUID actorId, @PathVariable UUID incidentId,
                                         @RequestBody HttpRequests.Reassign body) {
        return controller.reassignIncident(actorId, incidentId, body.targetTeamId(), body.reason());
    }

    // ---------------------------------------------------------------- history

    @GetMapping("/incidents/{incidentId}/timeline")
    public List<AuditEntryView> getIncidentTimeline(@ActingUser UUID actorId,
                                                    @PathVariable UUID incidentId) {
        return controller.getIncidentTimeline(actorId, incidentId);
    }

    @GetMapping("/incidents/{incidentId}/notifications")
    public List<NotificationView> getIncidentNotifications(@ActingUser UUID actorId,
                                                           @PathVariable UUID incidentId) {
        return controller.getIncidentNotifications(actorId, incidentId);
    }

    @GetMapping("/incidents/{incidentId}/escalations")
    public List<EscalationView> getIncidentEscalations(@ActingUser UUID actorId,
                                                       @PathVariable UUID incidentId) {
        return controller.getIncidentEscalations(actorId, incidentId);
    }

    // ---------------------------------------------------------------- admin

    @GetMapping("/admin/notifications/dead-lettered")
    public List<NotificationView> listDeadLetteredNotifications(@ActingUser UUID adminId) {
        return controller.listDeadLetteredNotifications(adminId);
    }

    @PostMapping("/admin/notifications/{notificationId}/replay")
    public NotificationView replayNotification(@ActingUser UUID adminId,
                                               @PathVariable UUID notificationId) {
        return controller.replayNotification(adminId, notificationId);
    }

    @GetMapping("/admin/users/{user}/activity")
    public List<AuditEntryView> getUserActivity(@ActingUser UUID adminId, @PathVariable String user) {
        return controller.getUserActivity(adminId, userId(user));
    }

    /**
     * A user named in the path, by id or username.
     */
    private UUID userId(String user) {
        return controller.findUserId(user).orElseThrow(() -> new NotFoundException("User " + user + " not found"));
    }
}
