package escalations.service;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox commands every {@code outbox.relay.interval}, and once an hour deletes commands published more
 * than 7 days ago and processed-event marks older than 30 days (ADR D14).
 */
@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    static final Duration KEEP_PUBLISHED = Duration.ofDays(7);
    static final Duration KEEP_PROCESSED = Duration.ofDays(30);

    private final OutboxService outbox;
    private final EscalationService escalations;
    private final Clock clock;

    public OutboxRelayJob(OutboxService outbox, EscalationService escalations, Clock clock) {
        this.outbox = outbox;
        this.escalations = escalations;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval}")
    public void relay() {
        try {
            int confirmed = outbox.relayPending();
            if (confirmed > 0) {
                log.debug("Published {} command(s)", confirmed);
            }
        } catch (RuntimeException e) {
            // e.g. database or RabbitMQ unavailable; the next run tries again
            log.error("Outbox relay run failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cleanUp() {
        try {
            int commands = outbox.deletePublishedBefore(clock.instant().minus(KEEP_PUBLISHED));
            int marks = escalations.deleteProcessedBefore(clock.instant().minus(KEEP_PROCESSED));
            log.info("Deleted {} published command(s) and {} processed-event mark(s)", commands, marks);
        } catch (RuntimeException e) {
            log.error("Clean-up failed: {}", e.getMessage());
        }
    }
}
