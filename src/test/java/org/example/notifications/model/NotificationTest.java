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
        NotificationView view = notification.toView();
        assertThat(view.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(view.nextAttemptAt()).isEqualTo(NOW);
        assertThat(view.recipientEmail()).isEqualTo("dan@example.com");
    }

    @Test
    void retriesThenDeadLetters() {
        notification.markFailed("connection refused", NOW, RetryPolicy.DEFAULT);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.toView().nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        notification.markFailed("x", NOW, RetryPolicy.DEFAULT);
        notification.markFailed("x", NOW, RetryPolicy.DEFAULT);
        notification.markFailed("still down", NOW, RetryPolicy.DEFAULT);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTERED);
        assertThat(notification.getAttempts()).isEqualTo(4);
        assertThat(notification.toView().lastError()).isEqualTo("still down");
        assertThatThrownBy(() -> notification.markSent(NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void replayOnlyFromDeadLetter() {
        assertThatThrownBy(() -> notification.replay(NOW)).isInstanceOf(BusinessRuleException.class);

        for (int i = 0; i < RetryPolicy.DEFAULT.maxAttempts(); i++) {
            notification.markFailed("down", NOW, RetryPolicy.DEFAULT);
        }
        notification.replay(NOW);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isZero();
    }

    @Test
    void sentClearsRetryState() {
        notification.markFailed("down", NOW, RetryPolicy.DEFAULT);
        notification.markSent(NOW);

        NotificationView view = notification.toView();
        assertThat(view.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(view.sentAt()).isEqualTo(NOW);
        assertThat(view.lastError()).isNull();
        assertThat(view.nextAttemptAt()).isNull();
    }
}
