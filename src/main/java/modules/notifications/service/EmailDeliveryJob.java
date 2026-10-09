package modules.notifications.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends due notifications every {@code notifications.delivery.interval}.
 */
@Component
public class EmailDeliveryJob {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryJob.class);

    private final NotificationService notifications;

    public EmailDeliveryJob(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${notifications.delivery.interval}")
    public void run() {
        try {
            int attempted = notifications.deliverDue();
            if (attempted > 0) {
                log.debug("Attempted {} notification(s)", attempted);
            }
        } catch (RuntimeException e) {
            // e.g. database unavailable; the next run tries again
            log.error("Email delivery run failed", e);
        }
    }
}
