package org.example.notifications.model;

import java.time.Instant;
import java.util.UUID;

/**
 * @param nextAttemptAt null when sent or dead-lettered
 * @param lastError     null unless the last attempt failed
 */
public record NotificationView(UUID id, UUID incidentId, UUID recipientId, String recipientEmail,
                               NotificationReason reason, String subject, NotificationStatus status, int attempts,
                               Instant nextAttemptAt, String lastError, Instant createdAt, Instant sentAt) {
}
