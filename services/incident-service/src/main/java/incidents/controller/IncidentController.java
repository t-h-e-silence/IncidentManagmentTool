package incidents.controller;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

import incidents.model.Actor;
import incidents.model.AddCommentCommand;
import incidents.model.CategoryRouting;
import incidents.model.CommentView;
import incidents.model.IncidentChange;
import incidents.model.IncidentEvent;
import incidents.model.IncidentStatus;
import incidents.model.IncidentSummary;
import incidents.model.IncidentView;
import incidents.model.ReportIncidentCommand;
import incidents.model.Severity;
import incidents.model.UpdateIncidentCommand;
import incidents.service.IdempotencyService;
import incidents.service.IncidentService;
import incidents.service.OrganizationClient;
import incidents.service.OutboxService;
import incidents.web.ActingUser;
import incidents.web.exception.BusinessRuleException;
import incidents.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The incident endpoints, on this service's own port (no API gateway, ADR D13), with the paths, bodies and status
 * codes of the monolith:
 *
 * <ul>
 *   <li>{@code GET /incidents}, {@code GET /teams/{teamId}/incidents}, {@code GET /users/{user}/incidents} — list
 *       incidents: all, by owning team, by reporter</li>
 *   <li>{@code POST /incidents} — create an incident</li>
 *   <li>{@code PATCH /incidents/{id}} — update name, description, severity (escalation / de-escalation) and status</li>
 *   <li>{@code GET /incidents/{id}}, {@code POST /incidents/{id}/comments} — view, comment</li>
 * </ul>
 *
 * <p>The caller is the {@value ActingUser#HEADER} header (user id or username); each method checks that the caller
 * is an active user (organization-service, cached). Each change and its events commit together in one transaction:
 * one event per change, sharing one correlation id per request, stored in the outbox (ADR D3, D4). The history,
 * escalation records and emails follow from those events in audit-, escalation- and notification-service.
 *
 * <p>The writing endpoints accept an optional {@value IdempotencyService#HEADER} header (ADR D9).
 */
@RestController
@Transactional
public class IncidentController {

    private static final Logger log = LoggerFactory.getLogger(IncidentController.class);

    private final OrganizationClient organization;
    private final IncidentService incidents;
    private final OutboxService outbox;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public IncidentController(OrganizationClient organization, IncidentService incidents, OutboxService outbox,
                              IdempotencyService idempotency, Clock clock) {
        this.organization = organization;
        this.incidents = incidents;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- list incidents

    /**
     * Every incident, in any status, newest first.
     */
    @GetMapping("/incidents")
    @Transactional(readOnly = true)
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "List all incidents", description = "Every incident in any status, newest first.")
    public List<IncidentSummary> listAllIncidents(@ActingUser UUID actorId) {
        organization.getActiveActor(actorId);
        return incidents.listAll();
    }

    /**
     * Incidents a team owns, in any status, newest first.
     */
    @GetMapping("/teams/{teamId}/incidents")
    @Transactional(readOnly = true)
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "List a team's incidents", description = "Incidents the team owns now, in any status, "
            + "newest first. 404 if the team does not exist.")
    public List<IncidentSummary> listTeamIncidents(
            @ActingUser UUID actorId,
            @Parameter(description = "Team id", example = "10000000-0000-0000-0000-000000000002")
            @PathVariable UUID teamId) {
        organization.getActiveActor(actorId);
        organization.getTeam(teamId);
        return incidents.listByTeam(teamId);
    }

    /**
     * Incidents a user reported, newest first.
     *
     * @param user user id or username
     */
    @GetMapping("/users/{user}/incidents")
    @Transactional(readOnly = true)
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "List incidents reported by a user", description = "Newest first. 404 if no such user.")
    public List<IncidentSummary> listUserIncidents(
            @ActingUser UUID actorId,
            @Parameter(description = "Username or user id", example = "bob") @PathVariable String user) {
        organization.getActiveActor(actorId);
        UUID userId = organization.findUserId(user)
                .orElseThrow(() -> new NotFoundException("User " + user + " not found"));
        return incidents.listReportedBy(userId);
    }

    // ---------------------------------------------------------------- create

    /**
     * Reports an incident; the category decides the owning team, which is emailed.
     */
    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "Create an incident", description = """
            Any active user. The category decides the owning team, whose members are emailed. The new incident is \
            `OPEN`. A reporter may set at most `SEV2` (409 otherwise); `title` (≤ 200), `categoryId` and \
            `severity` are required, `description` (≤ 5000) is optional.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(
            name = "Database incident", value = """
            {"title": "Checkout is slow", "description": "Pages take 10 s to load",
             "categoryId": "30000000-0000-0000-0000-000000000003", "severity": "SEV2"}""")))
    public IncidentView createIncident(
            @ActingUser UUID actorId,
            @Parameter(description = "Optional, ≤ 100 characters, e.g. a UUID per user action. A repeat with the same key and body within 24 h returns the first response without changing anything again; the same key with another body is 409", example = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b")
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody ReportIncidentCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        return idempotency.execute(idempotencyKey, actor.id(), "POST /incidents", command, IncidentView.class, () -> {
            String correlationId = newCorrelationId();
            CategoryRouting routing = organization.getRouting(command.categoryId());
            IncidentView incident = incidents.create(actor, command, routing.categoryName(), routing.teamId());
            outbox.publish(IncidentEvent.created(incident, actor, correlationId, clock.instant()));
            log.info("{} reported incident {} ({}, {}) for team {}", actor.name(), incident.id(), incident.severity(),
                    routing.categoryName(), incident.teamId());
            return incident;
        });
    }

    // ---------------------------------------------------------------- update

    /**
     * Updates an incident; all given changes succeed together or none does. In order:
     * <ol>
     *   <li><b>title / description</b> — by the owning team or the reporter; no emails.</li>
     *   <li><b>severity</b> — by the owning team, with a reason. Higher = <b>escalation</b>, optionally handing the
     *       incident over to {@code targetTeamId}; lower = <b>de-escalation</b>, same team.</li>
     *   <li><b>status</b> — by the owning team, see {@link IncidentStatus}. A reason is needed to resolve, cancel
     *       or reopen.</li>
     * </ol>
     * Each step that changes something publishes its own event.
     */
    @PatchMapping("/incidents/{incidentId}")
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "Update an incident: name, description, severity, status", description = """
            Fields left out stay unchanged; at least one change is needed. Applied in this order, all or nothing:

            1. **title / description** — owning team or reporter; no emails.
            2. **severity** + **reason** — owning team. Higher = **escalation**, optionally handing the incident over \
            to `targetTeamId` (it becomes `OPEN` for that team); lower = **de-escalation**, same team \
            (`targetTeamId` → 409). Both are recorded and emailed to the owning team, the previous team (on \
            hand-over) and the reporter.
            3. **status** — owning team: `OPEN → IN_PROGRESS → IN_REVIEW → RESOLVED → CLOSED`, \
            `IN_REVIEW → IN_PROGRESS`, `RESOLVED → IN_PROGRESS` (reopen), `OPEN | IN_PROGRESS | IN_REVIEW → \
            CANCELLED`. **reason** is required to resolve, cancel or reopen. Emails: acknowledged → reporter; \
            resolved, reopened, cancelled → team and reporter.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "Rename", value = "{\"title\": \"Primary DB slow\"}"),
            @ExampleObject(name = "Describe", value = "{\"description\": \"Timeouts on checkout and login\"}"),
            @ExampleObject(name = "Acknowledge", value = "{\"status\": \"IN_PROGRESS\"}"),
            @ExampleObject(name = "Escalate", value = "{\"severity\": \"SEV1\", \"reason\": \"replica lag growing\"}"),
            @ExampleObject(name = "Escalate and hand over", value = """
                    {"severity": "SEV1", "targetTeamId": "10000000-0000-0000-0000-000000000001",
                     "reason": "connection pool of the payments service"}"""),
            @ExampleObject(name = "De-escalate", value = "{\"severity\": \"SEV3\", \"reason\": \"contained\"}"),
            @ExampleObject(name = "Submit for review", value = "{\"status\": \"IN_REVIEW\"}"),
            @ExampleObject(name = "Resolve", value = "{\"status\": \"RESOLVED\", \"reason\": \"index rebuilt\"}"),
            @ExampleObject(name = "Close", value = "{\"status\": \"CLOSED\"}"),
            @ExampleObject(name = "Cancel", value = "{\"status\": \"CANCELLED\", \"reason\": \"duplicate\"}"),
            @ExampleObject(name = "Several at once", value = """
                    {"title": "Primary DB rejects writes", "severity": "SEV1", "status": "IN_PROGRESS",
                     "reason": "disk full"}""")}))
    public IncidentView updateIncident(
            @ActingUser UUID actorId,
            @Parameter(description = "Incident id; demo data: `40000000-0000-0000-0000-000000000001` (OPEN, Database team), `…0002` (IN_PROGRESS, Network), `…0003` (RESOLVED, Platform)",
                    example = "40000000-0000-0000-0000-000000000001")
            @PathVariable UUID incidentId,
            @Parameter(description = "Optional, ≤ 100 characters, e.g. a UUID per user action. A repeat with the same key and body within 24 h returns the first response without changing anything again; the same key with another body is 409", example = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b")
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody UpdateIncidentCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        return idempotency.execute(idempotencyKey, actor.id(), "PATCH /incidents/" + incidentId, command,
                IncidentView.class, () -> {
                    String correlationId = newCorrelationId();
                    IncidentView incident = incidents.get(incidentId);
                    if (command.changesDetails()) {
                        incident = updateDetails(actor, incidentId, command, correlationId);
                    }
                    if (command.severity() != null) {
                        incident = changeSeverity(actor, incident, command, correlationId);
                    }
                    if (command.status() != null) {
                        incident = changeStatus(actor, incidentId, command.status(), command.reason(), correlationId);
                    }
                    return incident;
                });
    }

    // ---------------------------------------------------------------- view, comment

    @GetMapping("/incidents/{incidentId}")
    @Transactional(readOnly = true)
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "View an incident", description = "With its comments, oldest first.")
    public IncidentView getIncident(
            @ActingUser UUID actorId,
            @Parameter(description = "Incident id; demo data: `40000000-0000-0000-0000-000000000001` (OPEN, Database team), `…0002` (IN_PROGRESS, Network), `…0003` (RESOLVED, Platform)",
                    example = "40000000-0000-0000-0000-000000000001")
            @PathVariable UUID incidentId) {
        organization.getActiveActor(actorId);
        return incidents.get(incidentId);
    }

    /**
     * By a member of the owning team or the reporter, while the incident is active.
     */
    @PostMapping("/incidents/{incidentId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "Comment on an incident", description = "Owning team or reporter, while the incident is "
            + "`OPEN`, `IN_PROGRESS` or `IN_REVIEW`. `text` ≤ 5000.")
    public CommentView addComment(
            @ActingUser UUID actorId,
            @Parameter(description = "Incident id; demo data: `40000000-0000-0000-0000-000000000001` (OPEN, Database team), `…0002` (IN_PROGRESS, Network), `…0003` (RESOLVED, Platform)",
                    example = "40000000-0000-0000-0000-000000000001")
            @PathVariable UUID incidentId,
            @Parameter(description = "Optional, ≤ 100 characters, e.g. a UUID per user action. A repeat with the same key and body within 24 h returns the first response without changing anything again; the same key with another body is 409", example = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b")
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody AddCommentCommand command) {
        Actor actor = organization.getActiveActor(actorId);
        return idempotency.execute(idempotencyKey, actor.id(), "POST /incidents/" + incidentId + "/comments", command,
                CommentView.class, () -> {
                    CommentView comment = incidents.addComment(actor, incidentId, command.text());
                    outbox.publish(IncidentEvent.commentAdded(incidents.get(incidentId), comment, actor,
                            newCorrelationId(), clock.instant()));
                    log.info("{} commented on incident {}", actor.name(), incidentId);
                    return comment;
                });
    }

    // ---------------------------------------------------------------- update steps

    private IncidentView updateDetails(Actor actor, UUID incidentId, UpdateIncidentCommand command,
                                       String correlationId) {
        IncidentChange change = incidents.updateDetails(actor, incidentId, command.title(), command.description());
        outbox.publish(IncidentEvent.detailsUpdated(change, actor, correlationId, clock.instant()));
        log.info("{} updated title/description of incident {}", actor.name(), incidentId);
        return change.after();
    }

    /**
     * Escalation (severity up, optional hand-over) or de-escalation (severity down, same team). Atomic here; the
     * escalation record and its emails follow from the event (ADR D2, D3).
     */
    private IncidentView changeSeverity(Actor actor, IncidentView current, UpdateIncidentCommand command,
                                        String correlationId) {
        Severity severity = command.severity();
        UUID targetTeamId = command.targetTeamId();
        if (severity == current.severity()) {
            throw new BusinessRuleException("Severity is already " + severity);
        }
        boolean raise = severity.isHigherThan(current.severity());
        IncidentChange change;
        if (raise) {
            if (targetTeamId != null) {
                organization.requireActiveTeam(targetTeamId);
            }
            change = incidents.escalate(actor, current.id(), severity, targetTeamId);
        } else {
            if (targetTeamId != null) {
                throw new BusinessRuleException("Only an escalation (severity raise) can hand the incident over to "
                        + "another team");
            }
            change = incidents.deEscalate(actor, current.id(), severity);
        }
        outbox.publish(IncidentEvent.severityChanged(raise, change, command.reason(), actor, correlationId,
                clock.instant()));
        log.info("{} {} incident {}: {} -> {}, team {} -> {}", actor.name(), raise ? "escalated" : "de-escalated",
                current.id(), change.before().severity(), change.after().severity(), change.before().teamId(),
                change.after().teamId());
        return change.after();
    }

    private IncidentView changeStatus(Actor actor, UUID incidentId, IncidentStatus status, String note,
                                      String correlationId) {
        IncidentChange change = incidents.changeStatus(actor, incidentId, status, note);
        outbox.publish(IncidentEvent.statusChanged(change, note, actor, correlationId, clock.instant()));
        log.info("{} moved incident {} from {} to {}", actor.name(), incidentId, change.before().status(),
                change.after().status());
        return change.after();
    }

    private static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
