package org.example.controller.model;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import org.example.common.model.Severity;
import org.example.common.model.Text;
import org.example.incidents.model.IncidentStatus;

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
public record UpdateIncidentCommand(
        @Schema(description = "New name (≤ 200)", example = "Primary DB slow") String title,
        @Schema(description = "New description (≤ 5000); blank clears it") String description,
        @Schema(description = "Higher = escalation, lower = de-escalation; needs reason") Severity severity,
        @Schema(description = "Only with an escalation: hand over to this active team",
                example = "10000000-0000-0000-0000-000000000001") UUID targetTeamId,
        @Schema(description = "Next lifecycle step") IncidentStatus status,
        @Schema(description = "Required with severity, and to resolve, cancel or reopen (≤ 2000)",
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
