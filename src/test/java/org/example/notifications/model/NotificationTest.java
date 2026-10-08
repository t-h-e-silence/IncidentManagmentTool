package org.example.notifications.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.TestData.*;

import java.time.Duration;
import java.util.UUID;

import org.example.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

class NotificationTest {

    private final Notification notification = new Notification(new EmailMessage(UUID.randomUUID(), recipient(DAN),
            NotificationReason.INCIDENT_CREATED, "subject", "body"), NOW);

    @Test
    void startsPendingAndDueNow() {
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(notification.getRecipientEmail()).isEqualTo("dan@example.com");
    }

    @Test
    void retriesThenDeadLetters() {
        notification.markFailed("connection refused", NOW, RetryPolicy.DEFAULT);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        notification.markFailed("x", NOW, RetryPolicy.DEFAULT);
        notification.markFailed("x", NOW, RetryPolicy.DEFAULT);
        notification.markFailed("still down", NOW, RetryPolicy.DEFAULT);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTERED);
        assertThat(notification.getAttempts()).isEqualTo(4);
        assertThat(notification.getLastError()).isEqualTo("still down");
        assertThatThrownBy(() -> notification.markSent(NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void sentClearsRetryState() {
        notification.markFailed("down", NOW, RetryPolicy.DEFAULT);
        notification.markSent(NOW);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(NOW);
        assertThat(notification.getLastError()).isNull();
        assertThat(notification.getNextAttemptAt()).isNull();
    }
}
