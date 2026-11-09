package notifications.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import notifications.model.NotificationMessage;
import notifications.model.NotificationPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationListenerTest {

    @Mock
    NotificationService notifications;

    private final NotificationMessage message = new NotificationMessage(UUID.randomUUID(), "incident.created", null,
            "corr-1", null, UUID.randomUUID(), Map.of());

    @Test
    void urgentEmailsGetTheirFirstAttemptAtOnce() {
        List<UUID> stored = List.of(UUID.randomUUID());
        when(notifications.accept(message, NotificationPriority.URGENT)).thenReturn(stored);

        new NotificationListener(notifications).onUrgent(message);

        verify(notifications).deliverNow(stored);
    }

    @Test
    void normalEmailsWaitForTheDeliveryJob() {
        new NotificationListener(notifications).onNormal(message);

        verify(notifications).accept(message, NotificationPriority.NORMAL);
        verify(notifications, never()).deliverNow(any());
    }
}
