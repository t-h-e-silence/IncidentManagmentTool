package modules.notifications.model;

import java.util.Objects;
import java.util.UUID;

import modules.common.model.Severity;

/**
 * What a regular incident email is about; the notifications module renders subject and body from it.
 *
 * @param teamName the owning team (the new one after a reassignment)
 * @param note     resolution note, or reason for a reassignment, reopening or cancellation; null if none
 */
public record IncidentNotice(UUID incidentId, String title, Severity severity, String teamName,
                             NotificationReason reason, String note) {

    public IncidentNotice {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(teamName, "teamName");
        Objects.requireNonNull(reason, "reason");
    }
}
