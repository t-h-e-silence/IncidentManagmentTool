package notifications.model;

import java.util.Objects;
import java.util.UUID;


/**
 * One ready-to-send email about an incident.
 */
public record EmailMessage(UUID incidentId, Recipient recipient, NotificationReason reason, String subject,
                           String body) {

    public EmailMessage {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }
}
