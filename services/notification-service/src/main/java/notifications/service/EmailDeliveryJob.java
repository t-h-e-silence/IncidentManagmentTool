package notifications.service;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends due notifications, urgent first, every {@code notifications.delivery.interval}; once an hour deletes
 * processed-message marks older than 30 days, far longer than any redelivery window (ADR D14).
 */
@Component
public class EmailDeliveryJob {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryJob.class);

    static final Duration KEEP_PROCESSED = Duration.ofDays(30);

    private final NotificationService notifications;
    private final Clock clock;

    public EmailDeliveryJob(NotificationService notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
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

    @Scheduled(cron = "0 0 * * * *")
    public void cleanUp() {
        try {
            int deleted = notifications.deleteProcessedBefore(clock.instant().minus(KEEP_PROCESSED));
            log.info("Deleted {} processed-message mark(s)", deleted);
        } catch (RuntimeException e) {
            log.error("Clean-up failed: {}", e.getMessage());
        }
    }
}
