package org.example.escalations.model;

import java.util.Objects;
import java.util.UUID;

import org.example.common.model.Severity;
import org.example.common.model.Text;

/**
 * What happened in one escalation, with the names needed for the emails.
 *
 * @param fromTeamId owning team before; equal to {@code toTeamId} if the incident was not handed over
 */
public record EscalationRecord(UUID incidentId, String incidentTitle, UUID actorId, String actorName,
                               String reason, Severity fromSeverity, Severity toSeverity,
                               UUID fromTeamId, String fromTeamName, UUID toTeamId, String toTeamName) {

    public static final int REASON_MAX_LENGTH = 2000;

    public EscalationRecord {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(incidentTitle, "incidentTitle");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorName, "actorName");
        reason = Text.require(reason, "reason", REASON_MAX_LENGTH);
        Objects.requireNonNull(fromSeverity, "fromSeverity");
        Objects.requireNonNull(toSeverity, "toSeverity");
        Objects.requireNonNull(fromTeamId, "fromTeamId");
        Objects.requireNonNull(fromTeamName, "fromTeamName");
        Objects.requireNonNull(toTeamId, "toTeamId");
        Objects.requireNonNull(toTeamName, "toTeamName");
    }

    public boolean handedOver() {
        return !fromTeamId.equals(toTeamId);
    }
}
