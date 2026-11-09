package notifications.service;

import java.util.List;
import java.util.UUID;

import notifications.RabbitConfig;
import notifications.model.NotificationMessage;
import notifications.model.NotificationPriority;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@value RabbitConfig#URGENT_QUEUE} and {@value RabbitConfig#NORMAL_QUEUE}, each with its own consumers,
 * so a flood of normal mail never holds up urgent mail (ADR D8). A message is acknowledged after its notifications
 * are stored; a failure is retried 3 times (1 s, 5 s, 25 s) and then dead-lettered to {@code <queue>.dlq}.
 */
@Component
public class NotificationListener {

    private final NotificationService notifications;

    public NotificationListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * Stores the emails, then gives them their first SMTP attempt at once, outside the storing transaction: an
     * email is never sent for a row that could still roll back.
     */
    @RabbitListener(queues = RabbitConfig.URGENT_QUEUE, containerFactory = RabbitConfig.URGENT_CONTAINER_FACTORY)
    public void onUrgent(NotificationMessage message) {
        List<UUID> stored = notifications.accept(message, NotificationPriority.URGENT);
        notifications.deliverNow(stored);
    }

    /**
     * Stores the emails; the delivery job sends them.
     */
    @RabbitListener(queues = RabbitConfig.NORMAL_QUEUE, containerFactory = RabbitConfig.NORMAL_CONTAINER_FACTORY)
    public void onNormal(NotificationMessage message) {
        notifications.accept(message, NotificationPriority.NORMAL);
    }
}
