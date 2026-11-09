package notifications.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import notifications.model.NotificationMessage;
import notifications.model.NotificationPriority;

/**
 * Emails about incidents. A consumed message becomes one stored notification per recipient; the delivery job sends
 * them, so a failing SMTP server never loses an email (ADR D8).
 */
public interface NotificationService {

    /**
     * Stores the emails one message asks for, unless the message was already consumed (RabbitMQ delivers at least
     * once). The notifications and the processed-message mark commit together.
     * <ul>
     *   <li>{@code incident.created} → the owning team (the active admins if it has no active members)</li>
     *   <li>{@code incident.status-changed}: acknowledged ({@code OPEN → IN_PROGRESS}) → the reporter; resolved,
     *       reopened ({@code RESOLVED → IN_PROGRESS}) and cancelled → the team and the reporter; other steps → none</li>
     *   <li>{@code notification.email-requested} → the one ready-made email it carries</li>
     * </ul>
     * The reporter is never emailed about their own action, and nobody gets the same email twice.
     *
     * @return ids of the stored notifications
     */
    List<UUID> accept(NotificationMessage message, NotificationPriority priority);

    /**
     * First SMTP attempt for just-stored urgent notifications; ones the delivery job holds right now are skipped.
     *
     * @return number of emails attempted
     */
    int deliverNow(List<UUID> notificationIds);

    /**
     * Sends due emails, urgent first; called by {@link EmailDeliveryJob}.
     *
     * @return number of emails attempted
     */
    int deliverDue();

    /**
     * @return number of processed-message marks deleted
     */
    int deleteProcessedBefore(Instant before);
}
