package org.example.controller.model;

import java.util.Objects;
import java.util.UUID;

import org.example.common.model.Severity;
import org.example.common.model.Text;

/**
 * A manual escalation request.
 *
 * @param severity     new severity; must be higher than the current one
 * @param targetTeamId team to hand the incident over to, or null to keep the owning team
 * @param reason       why; required, shown in the emails and the audit trail
 */
public record EscalateCommand(Severity severity, UUID targetTeamId, String reason) {

    public EscalateCommand {
        Objects.requireNonNull(severity, "severity");
        reason = Text.require(reason, "reason", 2000);
    }
}
