package audit.controller;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;

import audit.model.AuditEntryView;
import audit.service.AuditService;
import audit.service.OrganizationClient;
import audit.web.ActingUser;
import audit.web.exception.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public endpoint of audit-service, on its own port (no API gateway, ADR D13):
 * {@code GET /incidents/{id}/history} — the audit history of an incident. Same path and body as in the monolith.
 *
 * <p>The caller is the {@value ActingUser#HEADER} header (user id or username) and must be an active user.
 * The history is built from incident events, so the newest changes may show a few seconds late (ADR D3).
 */
@RestController
public class AuditController {

    private final OrganizationClient organization;
    private final AuditService audit;

    public AuditController(OrganizationClient organization, AuditService audit) {
        this.organization = organization;
        this.audit = audit;
    }

    /**
     * The audit history of an incident, oldest first: who did what, with details (from/to, reasons). 404 if nothing
     * was recorded for the incident: every incident gets an {@code INCIDENT_CREATED} entry, so it is unknown, or was
     * created a moment ago.
     */
    @GetMapping("/incidents/{incidentId}/history")
    @Operation(tags = OpenApiConfig.INCIDENTS, summary = "Audit history of an incident", description = """
            Oldest first: who did what and when, with details (from/to status, severity and team, reasons). \
            Changes made by one request share a `correlationId`. Built from incident events, so the newest \
            changes may show a few seconds late; 404 if nothing was recorded for the incident (yet).""")
    public List<AuditEntryView> getIncidentHistory(
            @ActingUser UUID actorId,
            @Parameter(description = "Incident id; demo data: `40000000-0000-0000-0000-000000000001` (created, commented), `…0002` (acknowledged), `…0003` (worked through to RESOLVED)",
                    example = "40000000-0000-0000-0000-000000000003")
            @PathVariable UUID incidentId) {
        organization.getActiveActor(actorId);
        List<AuditEntryView> timeline = audit.getIncidentTimeline(incidentId);
        if (timeline.isEmpty()) {
            throw new NotFoundException("Incident " + incidentId + " not found");
        }
        return timeline;
    }
}
