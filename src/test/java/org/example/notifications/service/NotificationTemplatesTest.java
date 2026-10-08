package org.example.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.example.common.model.Severity;
import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.NotificationReason;
import org.junit.jupiter.api.Test;

class NotificationTemplatesTest {

    private IncidentNotice notice(NotificationReason reason, String note) {
        return new IncidentNotice(UUID.randomUUID(), "DB down", Severity.SEV2, "Database", reason, note);
    }

    @Test
    void createdEmailNamesSeverityTitleAndTeam() {
        IncidentNotice notice = notice(NotificationReason.INCIDENT_CREATED, null);

        assertThat(NotificationTemplates.subject(notice)).isEqualTo("[SEV2] New incident: DB down");
        assertThat(NotificationTemplates.body(notice))
                .contains("reported for team Database", "Title:    DB down", notice.incidentId().toString());
    }

    @Test
    void resolvedEmailContainsTheNote() {
        IncidentNotice notice = notice(NotificationReason.INCIDENT_RESOLVED, "restarted replica");

        assertThat(NotificationTemplates.subject(notice)).startsWith("[SEV2] Resolved:");
        assertThat(NotificationTemplates.body(notice)).contains("Resolution: restarted replica");
    }

    @Test
    void cancelledEmailContainsTheReason() {
        IncidentNotice notice = notice(NotificationReason.INCIDENT_CANCELLED, "duplicate");

        assertThat(NotificationTemplates.subject(notice)).isEqualTo("[SEV2] Cancelled: DB down");
        assertThat(NotificationTemplates.body(notice)).contains("Reason: duplicate");
    }

    @Test
    void escalationAndRetiredEmailsAreNotRenderedHere() {
        for (NotificationReason reason : new NotificationReason[]{NotificationReason.INCIDENT_ESCALATED,
                NotificationReason.INCIDENT_DEESCALATED, NotificationReason.INCIDENT_REASSIGNED}) {
            assertThatThrownBy(() -> NotificationTemplates.subject(notice(reason, null)))
                    .as(reason.name()).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
