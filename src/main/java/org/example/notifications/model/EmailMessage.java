package org.example.notifications.model;

import java.util.Objects;
import java.util.UUID;

import org.example.common.model.Recipient;

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
