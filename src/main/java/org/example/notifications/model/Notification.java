package org.example.notifications.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.example.common.exception.BusinessRuleException;

/**
 * One email to one recipient and its delivery state. Created in the transaction of the action it is about,
 * sent later by the delivery job.
 */
@Entity
@Table(name = "notification", schema = "notifications")
public class Notification {

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    /** Address at the time the notification was created. */
    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationReason reason;

    @Column(nullable = false, length = 300)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Version
    private long version;

    protected Notification() {
        // for JPA
    }

    public Notification(EmailMessage message, Instant now) {
        this.id = UUID.randomUUID();
        this.incidentId = message.incidentId();
        this.recipientId = message.recipient().userId();
        this.recipientEmail = message.recipient().email();
        this.reason = message.reason();
        this.subject = message.subject().length() <= 300 ? message.subject() : message.subject().substring(0, 300);
        this.body = message.body();
        this.status = NotificationStatus.PENDING;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.nextAttemptAt = now;
    }

    public void markSent(Instant now) {
        requireDeliverable();
        attempts++;
        status = NotificationStatus.SENT;
        sentAt = now;
        nextAttemptAt = null;
        lastError = null;
    }

    /**
     * Records a failed attempt and schedules a retry, or dead-letters when no retries are left.
     */
    public void markFailed(String error, Instant now, RetryPolicy retryPolicy) {
        requireDeliverable();
        attempts++;
        lastError = error == null || error.isBlank() ? "unknown error"
                : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
        Optional<Duration> delay = retryPolicy.delayAfterFailedAttempt(attempts);
        if (delay.isPresent()) {
            status = NotificationStatus.RETRYING;
            nextAttemptAt = now.plus(delay.get());
        } else {
            status = NotificationStatus.DEAD_LETTERED;
            nextAttemptAt = null;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public int getAttempts() {
        return attempts;
    }

    private void requireDeliverable() {
        if (status != NotificationStatus.PENDING && status != NotificationStatus.RETRYING) {
            throw new BusinessRuleException("Notification " + id + " is " + status + " and cannot be delivered");
        }
    }
}
