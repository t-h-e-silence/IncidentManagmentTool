package org.example.notifications.service;

import java.util.List;

import org.example.common.model.Recipient;
import org.example.notifications.model.EmailMessage;
import org.example.notifications.model.IncidentNotice;

/**
 * Emails about incidents. Notifications are stored in the caller's transaction and sent after commit by
 * {@link EmailDeliveryJob}, so a failing SMTP server never fails the action that caused them.
 */
public interface NotificationService {

    /**
     * Regular incident email (created, acknowledged, resolved, reopened, cancelled), rendered from the notice;
     * one notification per recipient.
     */
    void notifyIncident(IncidentNotice notice, List<Recipient> recipients);

    /**
     * Stores ready-made emails, e.g. escalation emails built by the escalations module.
     */
    void send(List<EmailMessage> messages);

    /**
     * Sends due emails; called by {@link EmailDeliveryJob}.
     *
     * @return number of emails attempted
     */
    int deliverDue();
}
