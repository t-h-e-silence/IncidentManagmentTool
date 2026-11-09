package incidents.model;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;


/**
 * Changes to an incident ({@code PATCH /incidents/{id}}). Null fields stay unchanged; at least one change is needed.
 * Applied in this order: title/description, severity, status.
 *
 * @param title        new name
 * @param description  new description; blank clears it
 * @param severity     higher than now = escalation, lower = de-escalation; both are recorded and emailed and need
 *                     {@code reason}
 * @param targetTeamId only with an escalation: hands the incident over to this active team
 * @param status       next lifecycle step, e.g. {@code IN_REVIEW}, {@code RESOLVED}, {@code CLOSED}, {@code CANCELLED}
 * @param reason       required with {@code severity}, and to resolve, cancel or reopen; shown in emails and history
 */
@Schema(description = "Body of PATCH /incidents/{id}: fields left out stay unchanged; at least one change is needed")
public record UpdateIncidentCommand(
        @Schema(description = "New name (≤ 200). Owning team or reporter", example = "Primary DB slow") String title,
        @Schema(description = "New description (≤ 5000); blank clears it. Owning team or reporter", example = "Timeouts on checkout and login") String description,
        @Schema(description = "Owning team only. Higher than now = escalation (may hand over with targetTeamId), lower = de-escalation (same team); both need reason and are emailed", example = "SEV1") Severity severity,
        @Schema(description = "Only with an escalation: hand the incident over to this active team; it becomes OPEN for that team. Demo data: Platform `10000000-0000-0000-0000-000000000001`, Database `…0002`, Network `…0003`",
                example = "10000000-0000-0000-0000-000000000001") UUID targetTeamId,
        @Schema(description = "Owning team only: the next lifecycle step, see IncidentStatus. Resolving, cancelling and reopening need reason", example = "IN_PROGRESS") IncidentStatus status,
        @Schema(description = "Required with severity, and to resolve, cancel or reopen (≤ 2000); shown in emails and in the history",
                example = "replica lag growing") String reason) {

    public static final int REASON_MAX_LENGTH = 2000;

    public UpdateIncidentCommand {
        if (title == null && description == null && severity == null && targetTeamId == null && status == null) {
            throw new IllegalArgumentException(
                    "Nothing to update: give title, description, severity (+ targetTeamId) or status");
        }
        if (targetTeamId != null && severity == null) {
            throw new IllegalArgumentException("targetTeamId hands over with an escalation, so it needs a severity");
        }
        if (severity != null) {
            reason = Text.require(reason, "reason", REASON_MAX_LENGTH);
        }
    }

    public boolean changesDetails() {
        return title != null || description != null;
    }
}
