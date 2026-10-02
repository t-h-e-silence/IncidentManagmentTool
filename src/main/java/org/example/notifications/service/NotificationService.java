package org.example.notifications.service;

import java.util.List;
import java.util.UUID;

import org.example.common.model.Recipient;
import org.example.notifications.model.EmailMessage;
import org.example.notifications.model.IncidentNotice;
import org.example.notifications.model.NotificationView;

/**
 * Emails about incidents. Notifications are stored in the caller's transaction and sent after commit by
 * {@link EmailDeliveryJob}, so a failing SMTP server never fails the action that caused them.
 */
public interface NotificationService {

    /**
     * Regular incident email (created, acknowledged, resolved, reassigned), rendered from the notice;
     * one notification per recipient.
     */
    void notifyIncident(IncidentNotice notice, List<Recipient> recipients);

    /**
     * Stores ready-made emails, e.g. escalation emails built by the escalations module.
     */
    void send(List<EmailMessage> messages);

    /**
     * Emails about an incident, oldest first.
     */
    List<NotificationView> getForIncident(UUID incidentId);

    /**
     * Emails that failed every attempt.
     */
    List<NotificationView> getDeadLettered();

    /**
     * Sends a dead-lettered email again.
     *
     * @throws org.example.common.exception.NotFoundException     if the notification does not exist
     * @throws org.example.common.exception.BusinessRuleException if it is not dead-lettered
     */
    NotificationView replay(UUID notificationId);

    /**
     * Sends due emails; called by {@link EmailDeliveryJob}.
     *
     * @return number of emails attempted
     */
    int deliverDue();
}
